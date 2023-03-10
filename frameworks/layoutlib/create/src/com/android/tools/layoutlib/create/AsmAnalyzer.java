/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.layoutlib.create;

import com.android.tools.layoutlib.annotations.NotNull;
import com.android.tools.layoutlib.annotations.Nullable;
import com.android.tools.layoutlib.create.ICreateInfo.MethodReplacer;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Analyzes the input JAR using the ASM java bytecode manipulation library
 * to list the desired classes and their dependencies.
 */
public class AsmAnalyzer {

    public static class Result {
        private final Map<String, ClassReader> mFound;
        private final Map<String, ClassReader> mDeps;
        private final Map<String, InputStream> mFilesFound;
        private final Set<String> mReplaceMethodCallClasses;

        private Result(Map<String, ClassReader> found, Map<String, ClassReader> deps,
                Map<String, InputStream> filesFound, Set<String> replaceMethodCallClasses) {
            mFound = found;
            mDeps = deps;
            mFilesFound = filesFound;
            mReplaceMethodCallClasses = replaceMethodCallClasses;
        }

        public Map<String, ClassReader> getFound() {
            return mFound;
        }

        public Map<String, ClassReader> getDeps() {
            return mDeps;
        }

        public Map<String, InputStream> getFilesFound() {
            return mFilesFound;
        }

        public Set<String> getReplaceMethodCallClasses() {
            return mReplaceMethodCallClasses;
        }
    }

    // Note: a bunch of stuff has package-level access for unit tests. Consider it private.

    /** Output logger. */
    private final Log mLog;
    /** The input source JAR to parse. */
    private final List<String> mOsSourceJar;
    /** Keep all classes that derive from these one (these included). */
    private final String[] mDeriveFrom;
    /** Glob patterns of classes to keep, e.g. "com.foo.*" */
    private final String[] mIncludeGlobs;
    /** Glob patterns of classes to exclude.*/
    private final String[] mExcludedGlobs;
    /** Glob patterns of files to keep as is. */
    private final String[] mIncludeFileGlobs;
    /** Internal names of classes that contain method calls that need to be rewritten. */
    private final Set<String> mReplaceMethodCallClasses = new HashSet<>();
    /** Internal names of method calls that need to be rewritten. */
    private final MethodReplacer[] mMethodReplacers;

    /**
     * Creates a new analyzer.
     * @param log The log output.
     * @param osJarPath The input source JARs to parse.
     * @param deriveFrom Keep all classes that derive from these one (these included).
     * @param includeGlobs Glob patterns of classes to keep, e.g. "com.foo.*"
*        ("*" does not matches dots whilst "**" does, "." and "$" are interpreted as-is)
     * @param includeFileGlobs Glob patterns of files which are kept as is. This is only for files
     * @param methodReplacers names of method calls that need to be rewritten
     */
    public AsmAnalyzer(Log log, List<String> osJarPath, String[] deriveFrom, String[] includeGlobs,
            String[] excludedGlobs, String[] includeFileGlobs, MethodReplacer[] methodReplacers) {
        mLog = log;
        mOsSourceJar = osJarPath != null ? osJarPath : new ArrayList<>();
        mDeriveFrom = deriveFrom != null ? deriveFrom : new String[0];
        mIncludeGlobs = includeGlobs != null ? includeGlobs : new String[0];
        mExcludedGlobs = excludedGlobs != null ? excludedGlobs : new String[0];
        mIncludeFileGlobs = includeFileGlobs != null ? includeFileGlobs : new String[0];
        mMethodReplacers = methodReplacers;
    }

    /**
     * Starts the analysis using parameters from the constructor and returns the result.
     */
    @NotNull
    public Result analyze() throws IOException {
        Map<String, ClassReader> zipClasses = new TreeMap<>();
        Map<String, InputStream> filesFound = new TreeMap<>();

        parseZip(mOsSourceJar, zipClasses, filesFound);
        mLog.info("Found %d classes in input JAR%s.", zipClasses.size(),
                mOsSourceJar.size() > 1 ? "s" : "");

        Pattern[] includePatterns = Arrays.stream(mIncludeGlobs).parallel()
                .map(AsmAnalyzer::getPatternFromGlob)
                .toArray(Pattern[]::new);
        Pattern[] excludePatterns = Arrays.stream(mExcludedGlobs).parallel()
                .map(AsmAnalyzer::getPatternFromGlob)
                .toArray(Pattern[]::new);


        Map<String, ClassReader> found = new HashMap<>();
        findIncludes(mLog, includePatterns, mDeriveFrom, zipClasses, entry -> {
            if (!matchesAny(entry.getKey(), excludePatterns)) {
                found.put(entry.getKey(), entry.getValue());
            }
        });

        Map<String, ClassReader> deps = new HashMap<>();
        findDeps(mLog, zipClasses, found, keepEntry -> {
            if (!matchesAny(keepEntry.getKey(), excludePatterns)) {
                found.put(keepEntry.getKey(), keepEntry.getValue());
            }
        }, depEntry -> {
            if (!matchesAny(depEntry.getKey(), excludePatterns)) {
                deps.put(depEntry.getKey(), depEntry.getValue());
            }
        });

        mLog.info("Found %1$d classes to keep, %2$d class dependencies.",
                found.size(), deps.size());

        return new Result(found, deps, filesFound, mReplaceMethodCallClasses);
    }

    /**
     * Parses a JAR file and adds all the classes found to <code>classes</code>
     * and all other files to <code>filesFound</code>.
     *
     * @param classes The map of class name => ASM ClassReader. Class names are
     *                in the form "android.view.View".
     * @param filesFound The map of file name => InputStream. The file name is
     *                  in the form "android/data/dataFile".
     */
    void parseZip(List<String> jarPathList, Map<String, ClassReader> classes,
            Map<String, InputStream> filesFound) throws IOException {
        if (classes == null || filesFound == null) {
            return;
        }

        Pattern[] includeFilePatterns = new Pattern[mIncludeFileGlobs.length];
        for (int i = 0; i < mIncludeFileGlobs.length; ++i) {
            includeFilePatterns[i] = getPatternFromGlob(mIncludeFileGlobs[i]);
        }

        List<ForkJoinTask<?>> futures = new ArrayList<>();
        for (String jarPath : jarPathList) {
            futures.add(ForkJoinPool.commonPool().submit(() -> {
                try {
                    ZipFile zip = new ZipFile(jarPath);
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    ZipEntry entry;
                    while (entries.hasMoreElements()) {
                        entry = entries.nextElement();
                        if (entry.getName().endsWith(".class")) {
                            ClassReader cr = new ClassReader(zip.getInputStream(entry));
                            String className = classReaderToClassName(cr);
                            synchronized (classes) {
                                classes.put(className, cr);
                            }
                        } else {
                            for (Pattern includeFilePattern : includeFilePatterns) {
                                if (includeFilePattern.matcher(entry.getName()).matches()) {
                                    synchronized (filesFound) {
                                        filesFound.put(entry.getName(), zip.getInputStream(entry));
                                    }
                                    break;
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));
        }

        futures.forEach(ForkJoinTask::join);
    }

    /**
     * Utility that returns the fully qualified binary class name for a ClassReader.
     * E.g. it returns something like android.view.View.
     */
    static String classReaderToClassName(ClassReader classReader) {
        if (classReader == null) {
            return null;
        } else {
            return classReader.getClassName().replace('/', '.');
        }
    }

    /**
     * Utility that returns the fully qualified binary class name from a path-like FQCN.
     * E.g. it returns android.view.View from android/view/View.
     */
    private static String internalToBinaryClassName(String className) {
        if (className == null) {
            return null;
        } else {
            return className.replace('/', '.');
        }
    }

    private static boolean matchesAny(@Nullable String className, @NotNull Pattern[] patterns) {
        for (int i = 0; i < patterns.length; i++) {
            if (patterns[i].matcher(className).matches()) {
                return true;
            }
        }

        int dollarIdx = className.indexOf('$');
        if (dollarIdx != -1) {
            // This is an inner class, if the outer class matches, we also consider this a match
            return matchesAny(className.substring(0, dollarIdx), patterns);
        }

        return false;
    }

    /**
     * Process the "includes" arrays.
     * <p/>
     * This updates the in_out_found map.
     */
    private static void findIncludes(@NotNull Log log, @NotNull Pattern[] includePatterns,
            @NotNull String[] deriveFrom, @NotNull Map<String, ClassReader> zipClasses,
            @NotNull Consumer<Entry<String, ClassReader>> newInclude) throws FileNotFoundException {
        TreeMap<String, ClassReader> found = new TreeMap<>();

        log.debug("Find classes to include.");

        zipClasses.entrySet().stream()
                .filter(entry -> matchesAny(entry.getKey(), includePatterns))
                .forEach(entry -> found.put(entry.getKey(), entry.getValue()));

        for (String entry : deriveFrom) {
            findClassesDerivingFrom(entry, zipClasses, found);
        }

        found.entrySet().forEach(newInclude);
    }


    /**
     * Uses ASM to find the class reader for the given FQCN class name.
     * If found, insert it in the in_out_found map.
     * Returns the class reader object.
     */
    static ClassReader findClass(String className, Map<String, ClassReader> zipClasses,
            Map<String, ClassReader> inOutFound) throws FileNotFoundException {
        ClassReader classReader = zipClasses.get(className);
        if (classReader == null) {
            throw new FileNotFoundException(String.format("Class %s not found by ASM", className));
        }

        inOutFound.put(className, classReader);
        return classReader;
    }


    static Pattern getPatternFromGlob(String globPattern) {
     // transforms the glob pattern in a regexp:
        // - escape "." with "\."
        // - replace "*" by "[^.]*"
        // - escape "$" with "\$"
        // - add end-of-line match $
        globPattern = globPattern.replaceAll("\\$", "\\\\\\$");
        globPattern = globPattern.replaceAll("\\.", "\\\\.");
        // prevent ** from being altered by the next rule, then process the * rule and finally
        // the real ** rule (which is now @)
        globPattern = globPattern.replaceAll("\\*\\*", "@");
        globPattern = globPattern.replaceAll("\\*", "[^.]*");
        globPattern = globPattern.replaceAll("@", ".*");
        globPattern += "$";

        return Pattern.compile(globPattern);
    }

    /**
     * Checks all the classes defined in the JarClassName instance and uses BCEL to
     * determine if they are derived from the given FQCN super class name.
     * Inserts the super class and all the class objects found in the map.
     */
    static void findClassesDerivingFrom(String super_name, Map<String, ClassReader> zipClasses,
            Map<String, ClassReader> inOutFound) throws FileNotFoundException {
        findClass(super_name, zipClasses, inOutFound);

        for (Entry<String, ClassReader> entry : zipClasses.entrySet()) {
            String className = entry.getKey();
            if (super_name.equals(className)) {
                continue;
            }
            ClassReader classReader = entry.getValue();
            ClassReader parent_cr = classReader;
            while (parent_cr != null) {
                String parent_name = internalToBinaryClassName(parent_cr.getSuperName());
                if (parent_name == null) {
                    // not found
                    break;
                } else if (super_name.equals(parent_name)) {
                    inOutFound.put(className, classReader);
                    break;
                }
                parent_cr = zipClasses.get(parent_name);
            }
        }
    }

    /**
     * Instantiates a new DependencyVisitor. Useful for unit tests.
     */
    DependencyVisitor getVisitor(Map<String, ClassReader> zipClasses,
            Map<String, ClassReader> inKeep,
            Map<String, ClassReader> outKeep,
            Map<String, ClassReader> inDeps,
            Map<String, ClassReader> outDeps) {
        return new DependencyVisitor(zipClasses, inKeep, outKeep, inDeps, outDeps);
    }

    /**
     * Finds all dependencies for all classes in keepClasses which are also
     * listed in zipClasses. Returns a map of all the dependencies found.
     */
    void findDeps(Log log,
            Map<String, ClassReader> zipClasses,
            Map<String, ClassReader> inOutKeepClasses,
            Consumer<Entry<String, ClassReader>> newKeep,
            Consumer<Entry<String, ClassReader>> newDep) {

        TreeMap<String, ClassReader> keep = new TreeMap<>(inOutKeepClasses);
        TreeMap<String, ClassReader> deps = new TreeMap<>();
        TreeMap<String, ClassReader> new_deps = new TreeMap<>();
        TreeMap<String, ClassReader> new_keep = new TreeMap<>();
        TreeMap<String, ClassReader> temp = new TreeMap<>();

        DependencyVisitor visitor = getVisitor(zipClasses,
                keep, new_keep,
                deps, new_deps);

        for (ClassReader cr : inOutKeepClasses.values()) {
            visitor.setClassName(cr.getClassName());
            cr.accept(visitor, 0 /* flags */);
        }

        while (new_deps.size() > 0 || new_keep.size() > 0) {
            new_deps.entrySet().forEach(newDep);
            new_keep.entrySet().forEach(newKeep);
            keep.putAll(new_keep);
            deps.putAll(new_deps);

            temp.clear();
            temp.putAll(new_deps);
            temp.putAll(new_keep);
            new_deps.clear();
            new_keep.clear();
            log.debug("Found %1$d to keep, %2$d dependencies.",
                    inOutKeepClasses.size(), deps.size());

            for (ClassReader cr : temp.values()) {
                visitor.setClassName(cr.getClassName());
                cr.accept(visitor, 0 /* flags */);
            }
        }
    }

    // ----------------------------------

    /**
     * Visitor to collect all the type dependencies from a class.
     */
    public class DependencyVisitor extends ClassVisitor {

        /** All classes found in the source JAR. */
        private final Map<String, ClassReader> mZipClasses;
        /** Classes from which dependencies are to be found. */
        private final Map<String, ClassReader> mInKeep;
        /** Dependencies already known. */
        private final Map<String, ClassReader> mInDeps;
        /** New dependencies found by this visitor. */
        private final Map<String, ClassReader> mOutDeps;
        /** New classes to keep as-is found by this visitor. */
        private final Map<String, ClassReader> mOutKeep;

        private String mClassName;

        /**
         * Creates a new visitor that will find all the dependencies for the visited class.
         * Types which are already in the zipClasses, keepClasses or inDeps are not marked.
         * New dependencies are marked in outDeps.
         *
         * @param zipClasses All classes found in the source JAR.
         * @param inKeep Classes from which dependencies are to be found.
         * @param inDeps Dependencies already known.
         * @param outDeps New dependencies found by this visitor.
         */
        public DependencyVisitor(Map<String, ClassReader> zipClasses,
                Map<String, ClassReader> inKeep,
                Map<String, ClassReader> outKeep,
                Map<String,ClassReader> inDeps,
                Map<String,ClassReader> outDeps) {
            super(Main.ASM_VERSION);
            mZipClasses = zipClasses;
            mInKeep = inKeep;
            mOutKeep = outKeep;
            mInDeps = inDeps;
            mOutDeps = outDeps;
        }

        private void setClassName(String className) {
            mClassName = className;
        }

        /**
         * Considers the given class name as a dependency.
         * If it does, add to the mOutDeps map.
         */
        public void considerName(String className) {
            if (className == null) {
                return;
            }

            className = internalToBinaryClassName(className);

            // exclude classes that have already been found or are marked to be excluded
            if (mInKeep.containsKey(className) ||
                    mOutKeep.containsKey(className) ||
                    mInDeps.containsKey(className) ||
                    mOutDeps.containsKey(className)) {
                return;
            }

            // exclude classes that are not part of the JAR file being examined
            ClassReader cr = mZipClasses.get(className);
            if (cr == null) {
                return;
            }

            try {
                // exclude classes that are part of the default JRE (the one executing this program)
                if (className.startsWith("java.") || className.startsWith("sun.") ||
                        getClass().getClassLoader().getParent().loadClass(className) != null) {
                    return;
                }
            } catch (ClassNotFoundException e) {
                // ignore
            }

            // accept this class:
            // - android classes are added to dependencies
            // - non-android classes are added to the list of classes to keep as-is (they don't need
            //   to be stubbed).
            if (className.contains("android")) {  // TODO make configurable
                mOutDeps.put(className, cr);
            } else {
                mOutKeep.put(className, cr);
            }
        }

        /**
         * Considers this array of names using considerName().
         */
        public void considerNames(String[] classNames) {
            if (classNames != null) {
                for (String className : classNames) {
                    considerName(className);
                }
            }
        }

        /**
         * Considers this signature or type signature by invoking the {@link SignatureVisitor}
         * on it.
         */
        public void considerSignature(String signature) {
            if (signature != null) {
                SignatureReader sr = new SignatureReader(signature);
                // SignatureReader.accept will call accessType so we don't really have
                // to differentiate where the signature comes from.
                sr.accept(new MySignatureVisitor());
            }
        }

        /**
         * Considers this {@link Type}. For arrays, the element type is considered.
         * If the type is an object, its internal name is considered. If it is a method type,
         * iterate through the argument and return types.
         */
        public void considerType(Type t) {
            if (t != null) {
                if (t.getSort() == Type.ARRAY) {
                    t = t.getElementType();
                }
                if (t.getSort() == Type.OBJECT) {
                    considerName(t.getInternalName());
                }
                if (t.getSort() == Type.METHOD) {
                    for (Type type : t.getArgumentTypes()) {
                        considerType(type);
                    }
                    considerType(t.getReturnType());
                }
            }
        }

        /**
         * Considers a descriptor string. The descriptor is converted to a {@link Type}
         * and then considerType() is invoked.
         */
        public void considerDesc(String desc) {
            if (desc != null) {
                try {
                    Type t = Type.getType(desc);
                    considerType(t);
                } catch (ArrayIndexOutOfBoundsException e) {
                    // ignore, not a valid type.
                }
            }
        }

        // ---------------------------------------------------
        // --- ClassVisitor, FieldVisitor
        // ---------------------------------------------------

        // Visits a class header
        @Override
        public void visit(int version, int access, String name,
                String signature, String superName, String[] interfaces) {
            // signature is the signature of this class. May be null if the class is not a generic
            // one, and does not extend or implement generic classes or interfaces.

            if (signature != null) {
                considerSignature(signature);
            }

            // superName is the internal of name of the super class (see getInternalName).
            // For interfaces, the super class is Object. May be null but only for the Object class.
            considerName(superName);

            // interfaces is the internal names of the class's interfaces (see getInternalName).
            // May be null.
            considerNames(interfaces);
        }


        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            // desc is the class descriptor of the annotation class.
            considerDesc(desc);
            return new MyAnnotationVisitor();
        }

        @Override
        public void visitAttribute(Attribute attr) {
            // pass
        }

        // Visits the end of a class
        @Override
        public void visitEnd() {
            // pass
        }

        private class MyFieldVisitor extends FieldVisitor {

            public MyFieldVisitor() {
                super(Main.ASM_VERSION);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                // desc is the class descriptor of the annotation class.
                considerDesc(desc);
                return new MyAnnotationVisitor();
            }

            @Override
            public void visitAttribute(Attribute attr) {
                // pass
            }

            // Visits the end of a class
            @Override
            public void visitEnd() {
                // pass
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc,
                String signature, Object value) {
            // desc is the field's descriptor (see Type).
            considerDesc(desc);

            // signature is the field's signature. May be null if the field's type does not use
            // generic types.
            considerSignature(signature);

            return new MyFieldVisitor();
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            // name is the internal name of an inner class (see getInternalName).
            considerName(name);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                String signature, String[] exceptions) {
            // desc is the method's descriptor (see Type).
            considerDesc(desc);
            // signature is the method's signature. May be null if the method parameters, return
            // type and exceptions do not use generic types.
            considerSignature(signature);

            return new MyMethodVisitor(mClassName);
        }

        @Override
        public void visitOuterClass(String owner, String name, String desc) {
            // pass
        }

        @Override
        public void visitSource(String source, String debug) {
            // pass
        }


        // ---------------------------------------------------
        // --- MethodVisitor
        // ---------------------------------------------------

        private class MyMethodVisitor extends MethodVisitor {

            private String mOwnerClass;

            public MyMethodVisitor(String ownerClass) {
                super(Main.ASM_VERSION);
                mOwnerClass = ownerClass;
            }


            @Override
            public AnnotationVisitor visitAnnotationDefault() {
                return new MyAnnotationVisitor();
            }

            @Override
            public void visitCode() {
                // pass
            }

            // field instruction
            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                // owner is the class that declares the field.
                considerName(owner);
                // desc is the field's descriptor (see Type).
                considerDesc(desc);
            }

            @Override
            public void visitFrame(int type, int local, Object[] local2, int stack, Object[] stack2) {
                // pass
            }

            @Override
            public void visitIincInsn(int var, int increment) {
                // pass -- an IINC instruction
            }

            @Override
            public void visitInsn(int opcode) {
                // pass -- a zero operand instruction
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                // pass -- a single int operand instruction
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                // pass -- a jump instruction
            }

            @Override
            public void visitLabel(Label label) {
                // pass -- a label target
            }

            // instruction to load a constant from the stack
            @Override
            public void visitLdcInsn(Object cst) {
                if (cst instanceof Type) {
                    considerType((Type) cst);
                }
            }

            @Override
            public void visitLineNumber(int line, Label start) {
                // pass
            }

            @Override
            public void visitLocalVariable(String name, String desc,
                    String signature, Label start, Label end, int index) {
                // desc is the type descriptor of this local variable.
                considerDesc(desc);
                // signature is the type signature of this local variable. May be null if the local
                // variable type does not use generic types.
                considerSignature(signature);
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                // pass -- a lookup switch instruction
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {
                // pass
            }

            /**
             * If a method some.package.Class.Method(args) is called from some.other.Class,
             * @param owner some/package/Class
             * @param name Method
             * @param desc (args)returnType
             * @param sourceClass some/other/Class
             * @return if the method invocation needs to be replaced by some other class.
             */
            private boolean isReplacementNeeded(String owner, String name, String desc,
                    String sourceClass) {
                for (MethodReplacer replacer : mMethodReplacers) {
                    if (replacer.isNeeded(owner, name, desc, sourceClass)) {
                        return true;
                    }
                }
                return false;
            }

            // instruction that invokes a method
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc,
                    boolean itf) {

                // owner is the internal name of the method's owner class
                considerName(owner);
                // desc is the method's descriptor (see Type).
                considerDesc(desc);


                // Check if method needs to replaced by a call to a different method.
                if (isReplacementNeeded(owner, name, desc, mOwnerClass)) {
                    mReplaceMethodCallClasses.add(mOwnerClass);
                }
            }

            // instruction multianewarray, whatever that is
            @Override
            public void visitMultiANewArrayInsn(String desc, int dims) {

                // desc an array type descriptor.
                considerDesc(desc);
            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String desc,
                    boolean visible) {
                // desc is the class descriptor of the annotation class.
                considerDesc(desc);
                return new MyAnnotationVisitor();
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                // pass -- table switch instruction

            }

            @Override
            public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                // type is the internal name of the type of exceptions handled by the handler,
                // or null to catch any exceptions (for "finally" blocks).
                considerName(type);
            }

            // type instruction
            @Override
            public void visitTypeInsn(int opcode, String type) {
                // type is the operand of the instruction to be visited. This operand must be the
                // internal name of an object or array class.
                considerName(type);
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                // pass -- local variable instruction
            }
        }

        private class MySignatureVisitor extends SignatureVisitor {

            public MySignatureVisitor() {
                super(Main.ASM_VERSION);
            }

            // ---------------------------------------------------
            // --- SignatureVisitor
            // ---------------------------------------------------

            private String mCurrentSignatureClass = null;

            // Starts the visit of a signature corresponding to a class or interface type
            @Override
            public void visitClassType(String name) {
                mCurrentSignatureClass = name;
                considerName(name);
            }

            // Visits an inner class
            @Override
            public void visitInnerClassType(String name) {
                if (mCurrentSignatureClass != null) {
                    mCurrentSignatureClass += "$" + name;
                    considerName(mCurrentSignatureClass);
                }
            }

            @Override
            public SignatureVisitor visitArrayType() {
                return new MySignatureVisitor();
            }

            @Override
            public void visitBaseType(char descriptor) {
                // pass -- a primitive type, ignored
            }

            @Override
            public SignatureVisitor visitClassBound() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitExceptionType() {
                return new MySignatureVisitor();
            }

            @Override
            public void visitFormalTypeParameter(String name) {
                // pass
            }

            @Override
            public SignatureVisitor visitInterface() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitInterfaceBound() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitParameterType() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitReturnType() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitSuperclass() {
                return new MySignatureVisitor();
            }

            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) {
                return new MySignatureVisitor();
            }

            @Override
            public void visitTypeVariable(String name) {
                // pass
            }

            @Override
            public void visitTypeArgument() {
                // pass
            }
        }


        // ---------------------------------------------------
        // --- AnnotationVisitor
        // ---------------------------------------------------

        private class MyAnnotationVisitor extends AnnotationVisitor {

            public MyAnnotationVisitor() {
                super(Main.ASM_VERSION);
            }

            // Visits a primitive value of an annotation
            @Override
            public void visit(String name, Object value) {
                // value is the actual value, whose type must be Byte, Boolean, Character, Short,
                // Integer, Long, Float, Double, String or Type
                if (value instanceof Type) {
                    considerType((Type) value);
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                // desc is the class descriptor of the nested annotation class.
                considerDesc(desc);
                return new MyAnnotationVisitor();
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return new MyAnnotationVisitor();
            }

            @Override
            public void visitEnum(String name, String desc, String value) {
                // desc is the class descriptor of the enumeration class.
                considerDesc(desc);
            }
        }
    }
}
