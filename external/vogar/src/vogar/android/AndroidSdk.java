/*
 * Copyright (C) 2010 The Android Open Source Project
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

package vogar.android;

import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import vogar.Classpath;
import vogar.Dexer;
import vogar.HostFileCache;
import vogar.Language;
import vogar.Log;
import vogar.Md5Cache;
import vogar.ModeId;
import vogar.commands.Command;
import vogar.commands.Mkdir;
import vogar.util.Strings;



/**
 * Android SDK commands such as adb, aapt and dx.
 */
public class AndroidSdk {

    private static final String D8_COMMAND_NAME = "d8";
    private static final String DX_COMMAND_NAME = "dx";
    private static final String ARBITRARY_BUILD_TOOL_NAME = D8_COMMAND_NAME;

    private final Log log;
    private final Mkdir mkdir;
    private final File[] compilationClasspath;
    private final String androidJarPath;
    private final String desugarJarPath;
    private final Md5Cache dexCache;
    private final Language language;

    public static Collection<File> defaultExpectations() {
        return Collections.singletonList(new File("libcore/expectations/knownfailures.txt"));
    }

    /**
     * Create an {@link AndroidSdk}.
     *
     * <p>Searches the PATH used to run this and scans the file system in order to determine the
     * compilation class path and android jar path.
     */
    public static AndroidSdk createAndroidSdk(
            Log log, Mkdir mkdir, ModeId modeId, Language language,
            boolean supportBuildFromSource) {
        List<String> path = new Command.Builder(log).args("which", ARBITRARY_BUILD_TOOL_NAME)
                .permitNonZeroExitStatus(true)
                .execute();
        if (path.isEmpty()) {
            throw new RuntimeException(ARBITRARY_BUILD_TOOL_NAME + " not found");
        }
        File buildTool = new File(path.get(0)).getAbsoluteFile();
        String buildToolDirString = getParentFileNOrLast(buildTool, 1).getName();

        List<String> adbPath = new Command.Builder(log)
                .args("which", "adb")
                .permitNonZeroExitStatus(true)
                .execute();

        File adb;
        if (!adbPath.isEmpty()) {
            adb = new File(adbPath.get(0));
        } else {
            adb = null;  // Could not find adb.
        }

        /*
         * Determine if we are running with a provided SDK or in the AOSP source tree.
         *
         * Android build tree (target):
         *  ${ANDROID_BUILD_TOP}/out/host/linux-x86/bin/aapt
         *  ${ANDROID_BUILD_TOP}/out/host/linux-x86/bin/adb
         *  ${ANDROID_BUILD_TOP}/out/host/linux-x86/bin/dx
         *  ${ANDROID_BUILD_TOP}/out/host/linux-x86/bin/desugar.jar
         *  ${ANDROID_BUILD_TOP}/out/target/common/obj/JAVA_LIBRARIES/core-libart_intermediates
         *      /classes.jar
         */

        File[] compilationClasspath;
        String androidJarPath;
        String desugarJarPath = null;

        // Accept that we are running in an SDK if the user has added the build-tools or
        // platform-tools to their path.
        boolean buildToolsPathValid = "build-tools".equals(getParentFileNOrLast(buildTool, 2)
                .getName());
        boolean isAdbPathValid = (adb != null) &&
                "platform-tools".equals(getParentFileNOrLast(adb, 1).getName());
        if (buildToolsPathValid || isAdbPathValid) {
            File sdkRoot = buildToolsPathValid
                    ? getParentFileNOrLast(buildTool, 3)  // if build tool path invalid then
                    : getParentFileNOrLast(adb, 2);  // adb must be valid.
            File newestPlatform = getNewestPlatform(sdkRoot);
            log.verbose("Using android platform: " + newestPlatform);
            compilationClasspath = new File[] { new File(newestPlatform, "android.jar") };
            androidJarPath = new File(newestPlatform.getAbsolutePath(), "android.jar")
                    .getAbsolutePath();
            log.verbose("using android sdk: " + sdkRoot);

            // There must be a desugar.jar in the build tool directory.
            desugarJarPath = buildToolDirString + "/desugar.jar";
            File desugarJarFile = new File(desugarJarPath);
            if (!desugarJarFile.exists()) {
                throw new RuntimeException("Could not find " + desugarJarPath);
            }
        } else if ("bin".equals(buildToolDirString)) {
            log.verbose("Using android source build mode to find dependencies.");
            String tmpJarPath = "prebuilts/sdk/current/public/android.jar";
            String androidBuildTop = System.getenv("ANDROID_BUILD_TOP");
            if (!com.google.common.base.Strings.isNullOrEmpty(androidBuildTop)) {
                tmpJarPath = androidBuildTop + "/prebuilts/sdk/current/public/android.jar";
            } else {
                log.warn("Assuming current directory is android build tree root.");
            }
            androidJarPath = tmpJarPath;

            String outDir = System.getenv("OUT_DIR");
            if (Strings.isNullOrEmpty(outDir)) {
                if (Strings.isNullOrEmpty(androidBuildTop)) {
                    outDir = ".";
                    log.warn("Assuming we are in android build tree root to find libraries.");
                } else {
                    log.verbose("Using ANDROID_BUILD_TOP to find built libraries.");
                    outDir = androidBuildTop;
                }
                outDir += "/out/";
            } else {
                log.verbose("Using OUT_DIR environment variable for finding built libs.");
                outDir += "/";
            }

            String hostOutDir = System.getenv("ANDROID_HOST_OUT");
            if (!Strings.isNullOrEmpty(hostOutDir)) {
                log.verbose("Using ANDROID_HOST_OUT to find host libraries.");
            } else {
                // Handle the case where lunch hasn't been run. Guess the architecture.
                log.warn("ANDROID_HOST_OUT not set. Assuming linux-x86");
                hostOutDir = outDir + "/host/linux-x86";
            }

            String desugarPattern = hostOutDir + "/framework/desugar.jar";
            File desugarJar = new File(desugarPattern);

            if (!desugarJar.exists()) {
                throw new RuntimeException("Could not find " + desugarPattern);
            }

            desugarJarPath = desugarJar.getPath();

            if (!supportBuildFromSource) {
                compilationClasspath = new File[]{};
            } else {
                String pattern = outDir +
                        "target/common/obj/JAVA_LIBRARIES/%s_intermediates/classes";
                if (modeId.isHost()) {
                    pattern = outDir + "host/common/obj/JAVA_LIBRARIES/%s_intermediates/classes";
                }
                pattern += ".jar";

                String[] jarNames = modeId.getJarNames();
                compilationClasspath = new File[jarNames.length];
                List<String> missingJars = new ArrayList<>();
                for (int i = 0; i < jarNames.length; i++) {
                    String jar = jarNames[i];
                    File file;
                    if (modeId.isHost()) {
                        if  ("conscrypt-hostdex".equals(jar)) {
                            jar = "conscrypt-host-hostdex";
                        } else if ("core-icu4j-hostdex".equals(jar)) {
                            jar = "core-icu4j-host-hostdex";
                        }
                        file = new File(String.format(pattern, jar));
                    } else {
                        file = findApexJar(jar, pattern);
                        if (file.exists()) {
                            log.verbose("Using jar " + jar + " from " + file);
                        } else {
                            missingJars.add(jar);
                        }
                    }
                    compilationClasspath[i] = file;
                }
                if (!missingJars.isEmpty()) {
                    logMissingJars(log, missingJars);
                    throw new RuntimeException("Unable to locate all jars needed for compilation");
                }
            }
        } else {
            throw new RuntimeException("Couldn't derive Android home from "
                    + ARBITRARY_BUILD_TOOL_NAME);
        }

        return new AndroidSdk(log, mkdir, compilationClasspath, androidJarPath, desugarJarPath,
                new HostFileCache(log, mkdir), language);
    }

    /** Logs jars that couldn't be found ands suggests a command for building them */
    private static void logMissingJars(Log log, List<String> missingJars) {
        StringBuilder makeCommand = new StringBuilder().append("m ");
        for (String jarName : missingJars) {
            String apex = apexForJar(jarName);
            log.warn("Missing compilation jar " + jarName +
                    (apex != null ? " from APEX " + apex : ""));
            makeCommand.append(jarName).append(" ");
        }
        log.info("Suggested make command: " + makeCommand);
    }

    /** Returns the name of the APEX a particular jar might be located in */
    private static String apexForJar(String jar) {
        if (jar.endsWith(".api.stubs")) {
            return null;  // API stubs aren't in any APEX.
        }
        return "com.android.art.testing";
    }

    /**
     * Depending on the build setup, jars might be located in the intermediates directory
     * for their APEX or not, so look in both places. Returns the last path searched, so
     * always non-null but possibly non-existent and so the caller should check.
     */
    private static File findApexJar(String jar, String filePattern) {
        String apex = apexForJar(jar);
        if (apex != null) {
            File file = new File(String.format(filePattern, jar + "." + apex));
            if (file.exists()) {
                return file;
            }
        }
        return new File(String.format(filePattern, jar));
    }

    @VisibleForTesting
    AndroidSdk(Log log, Mkdir mkdir, File[] compilationClasspath, String androidJarPath,
               String desugarJarPath, HostFileCache hostFileCache, Language language) {
        this.log = log;
        this.mkdir = mkdir;
        this.compilationClasspath = compilationClasspath;
        this.androidJarPath = androidJarPath;
        this.desugarJarPath = desugarJarPath;
        this.dexCache = new Md5Cache(log, "dex", hostFileCache);
        this.language = language;
    }

    // Goes up N levels in the filesystem hierarchy. Return the last file that exists if this goes
    // past /.
    private static File getParentFileNOrLast(File f, int n) {
        File lastKnownExists = f;
        for (int i = 0; i < n; i++) {
            File parentFile = lastKnownExists.getParentFile();
            if (parentFile == null) {
                return lastKnownExists;
            }
            lastKnownExists = parentFile;
        }
        return lastKnownExists;
    }

    /**
     * Returns the platform directory that has the highest API version. API
     * platform directories are named like "android-9" or "android-11".
     */
    private static File getNewestPlatform(File sdkRoot) {
        File newestPlatform = null;
        int newestPlatformVersion = 0;
        File[] platforms = new File(sdkRoot, "platforms").listFiles();
        if (platforms != null) {
            for (File platform : platforms) {
                try {
                    int version =
                            Integer.parseInt(platform.getName().substring("android-".length()));
                    if (version > newestPlatformVersion) {
                        newestPlatform = platform;
                        newestPlatformVersion = version;
                    }
                } catch (NumberFormatException ignore) {
                    // Ignore non-numeric preview versions like android-Honeycomb
                }
            }
        }
        if (newestPlatform == null) {
            throw new IllegalStateException("Cannot find newest platform in " + sdkRoot);
        }
        return newestPlatform;
    }

    public static Collection<File> defaultSourcePath() {
        return filterNonExistentPathsFrom("libcore/support/src/test/java",
                                          "external/mockwebserver/src/main/java/");
    }

    private static Collection<File> filterNonExistentPathsFrom(String... paths) {
        ArrayList<File> result = new ArrayList<File>();
        String buildRoot = System.getenv("ANDROID_BUILD_TOP");
        for (String path : paths) {
            File file = new File(buildRoot, path);
            if (file.exists()) {
                result.add(file);
            }
        }
        return result;
    }

    public File[] getCompilationClasspath() {
        return compilationClasspath;
    }

    /**
     * Converts all the .class files on 'classpath' into a dex file written to 'output'.
     *
     * @param multidex could the output be more than 1 dex file?
     * @param output the File for the classes.dex that will be generated as a result of this call.
     * @param outputTempDir a temporary directory which can store intermediate files generated.
     * @param classpath a list of files/directories containing .class files that are
     *                  merged together and converted into the output (dex) file.
     * @param dependentCp classes that are referenced in classpath but are not themselves on the
     *                    classpath must be listed in dependentCp, this is required to be able
     *                    resolve all class dependencies. The classes in dependentCp are <i>not</i>
     *                    included in the output dex file.
     * @param dexer Which dex tool to use
     */
    public void dex(boolean multidex, File output, File outputTempDir,
            Classpath classpath, Classpath dependentCp, Dexer dexer) {
        mkdir.mkdirs(output.getParentFile());

        String classpathSubKey = dexCache.makeKey(classpath);
        String cacheKey = null;
        if (classpathSubKey != null) {
            String multidexSubKey = "mdex=" + multidex;
            cacheKey = dexCache.makeKey(classpathSubKey, multidexSubKey);
            boolean cacheHit = dexCache.getFromCache(output, cacheKey);
            if (cacheHit) {
                log.verbose("dex cache hit for " + classpath);
                return;
            }
        }

        List<String> filePaths = new ArrayList<String>();
        for (File file : classpath.getElements()) {
          filePaths.add(file.getPath());
        }

        /*
         * We pass --core-library so that we can write tests in the
         * same package they're testing, even when that's a core
         * library package. If you're actually just using this tool to
         * execute arbitrary code, this has the unfortunate
         * side-effect of preventing "dx" from protecting you from
         * yourself.
         *
         * Memory options pulled from build/core/definitions.mk to
         * handle large dx input when building dex for APK.
         */

        Command.Builder builder = new Command.Builder(log);
        switch (dexer) {
            case DX:
                builder.args(DX_COMMAND_NAME);
                builder.args("-JXms16M").args("-JXmx1536M");
                builder.args("--min-sdk-version=" + language.getMinApiLevel());
                if (multidex) {
                    builder.args("--multi-dex");
                }
                builder.args("--dex")
                    .args("--output=" + output)
                    .args("--core-library")
                    .args(filePaths);
                builder.execute();
                break;
            case D8:
                List<String> sanitizedOutputFilePaths;
                try {
                    sanitizedOutputFilePaths = removeDexFilesForD8(filePaths);
                } catch (IOException e) {
                    throw new RuntimeException("Error while removing dex files from archive", e);
                }
                builder.args(D8_COMMAND_NAME);
                builder.args("-JXms16M").args("-JXmx1536M");

                // d8 will not allow compiling with a single dex file as the target, but if given
                // a directory name will start its output in classes.dex but may overflow into
                // multiple dex files. See b/189327238
                String outputPath = output.toString();
                String dexOverflowPath = null;
                if (outputPath.endsWith("/classes.dex")) {
                    dexOverflowPath = outputPath.replace("classes.dex", "classes2.dex");
                    outputPath = output.getParentFile().toString();
                }
                builder
                    .args("--min-api").args(language.getMinApiLevel())
                    .args("--output").args(outputPath)
                    .args(sanitizedOutputFilePaths);
                builder.execute();
                if (dexOverflowPath != null && new File(dexOverflowPath).exists()) {
                    // If we were expecting a single dex file and d8 overflows into two
                    // or more files than fail.
                    throw new RuntimeException("Dex file overflow " + dexOverflowPath
                        + ", try --multidex");
                }
                if (output.toString().endsWith(".jar")) {
                    try {
                        fixD8JarOutput(output, filePaths);
                    } catch (IOException e) {
                        throw new RuntimeException("Error while fixing d8 output", e);
                    }
                }
                break;
            default:
                throw new RuntimeException("Unsupported dexer: " + dexer);

        }

        dexCache.insert(cacheKey, output);
    }

    /**
     * Produces an output file like dx does. dx generates jar files containing all resources present
     * in the input files.
     * d8 only produces a jar file containing dex and none of the input resources, and
     * will produce no file at all if there are no .class files to process.
     */
    private static void fixD8JarOutput(File output, List<String> inputs) throws IOException {
        List<String> filesToMerge = new ArrayList<>(inputs);

        // JarOutputStream is not keen on appending entries to existing file so we move the output
        // files if it already exists.
        File outputCopy = null;
        if (output.exists()) {
            outputCopy = new File(output.toString() + ".copy");
            output.renameTo(outputCopy);
            filesToMerge.add(outputCopy.toString());
        }

        byte[] buffer = new byte[4096];
        try (JarOutputStream outputJar = new JarOutputStream(new FileOutputStream(output))) {
            for (String fileToMerge : filesToMerge) {
                copyJarContentExcludingClassFiles(buffer, fileToMerge, outputJar);
            }
        } finally {
            if (outputCopy != null) {
                outputCopy.delete();
            }
        }
    }

    /**
      * Removes DEX files from an archive and preserves the rest.
      */
    private List<String> removeDexFilesForD8(List<String> fileNames) throws IOException {
        byte[] buffer = new byte[4096];
        List<String> processedFiles = new ArrayList<>(fileNames.size());
        for (String inputFileName : fileNames) {
            String jarExtension = ".jar";
            String outputFileName;
            if (inputFileName.endsWith(jarExtension)) {
                outputFileName =
                    inputFileName.substring(0, inputFileName.length() - jarExtension.length())
                    + "-d8" + jarExtension;
            } else {
                outputFileName = inputFileName + "-d8" + jarExtension;
            }
            try (JarOutputStream outputJar =
                    new JarOutputStream(new FileOutputStream(outputFileName))) {
                copyJarContentExcludingFiles(buffer, inputFileName, outputJar, ".dex");
            }
            processedFiles.add(outputFileName);
        }
        return processedFiles;
    }

    private static void copyJarContentExcludingClassFiles(byte[] buffer, String inputJarName,
            JarOutputStream outputJar) throws IOException {
        copyJarContentExcludingFiles(buffer, inputJarName, outputJar, ".class");
    }

    private static void copyJarContentExcludingFiles(byte[] buffer, String inputJarName,
            JarOutputStream outputJar, String extensionToExclude) throws IOException {

        try (JarInputStream inputJar = new JarInputStream(new FileInputStream(inputJarName))) {
            for (JarEntry entry = inputJar.getNextJarEntry();
                    entry != null;
                    entry = inputJar.getNextJarEntry()) {
                if (entry.getName().endsWith(extensionToExclude)) {
                    continue;
                }

                // Skip directories as they can cause duplicates.
                if (entry.isDirectory()) {
                    continue;
                }

                outputJar.putNextEntry(entry);

                int length;
                while ((length = inputJar.read(buffer)) >= 0) {
                    if (length > 0) {
                        outputJar.write(buffer, 0, length);
                    }
                }
                outputJar.closeEntry();
            }
        }
    }

    public void packageApk(File apk, File manifest) {
        new Command(log, "aapt",
                "package",
                "-F", apk.getPath(),
                "-M", manifest.getPath(),
                "-I", androidJarPath,
                "--version-name", "1.0",
                "--version-code", "1").execute();
    }

    public void addToApk(File apk, File dex) {
        new Command(log, "aapt", "add", "-k", apk.getPath(), dex.getPath()).execute();
    }

    public void install(File apk) {
        new Command(log, "adb", "install", "-r", apk.getPath()).execute();
    }

    public void uninstall(String packageName) {
        new Command.Builder(log)
                .args("adb", "uninstall", packageName)
                .permitNonZeroExitStatus(true)
                .execute();
    }
}
