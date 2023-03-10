/*
 * Copyright (C) 2021 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.hilt.processor.internal.aggregateddeps;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import dagger.hilt.processor.internal.AggregatedElements;
import dagger.hilt.processor.internal.AnnotationValues;
import dagger.hilt.processor.internal.ClassNames;
import dagger.hilt.processor.internal.Processors;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * A class that represents the values stored in an {@link
 * dagger.hilt.processor.internal.aggregateddeps.AggregatedDeps} annotation.
 */
@AutoValue
abstract class AggregatedDepsMetadata {
  private static final String AGGREGATED_DEPS_PACKAGE = "hilt_aggregated_deps";

  enum DependencyType {
    MODULE,
    ENTRY_POINT,
    COMPONENT_ENTRY_POINT
  }

  abstract Optional<TypeElement> testElement();

  abstract ImmutableSet<TypeElement> componentElements();

  abstract DependencyType dependencyType();

  abstract TypeElement dependency();

  abstract ImmutableSet<TypeElement> replacedDependencies();

  /** Returns all aggregated deps in the aggregating package. */
  public static ImmutableSet<AggregatedDepsMetadata> from(Elements elements) {
    return AggregatedElements.from(AGGREGATED_DEPS_PACKAGE, ClassNames.AGGREGATED_DEPS, elements)
        .stream()
        .map(aggregatedElement -> create(aggregatedElement, elements))
        .collect(toImmutableSet());
  }

  private static AggregatedDepsMetadata create(TypeElement element, Elements elements) {
    AnnotationMirror annotationMirror =
        Processors.getAnnotationMirror(element, ClassNames.AGGREGATED_DEPS);

    ImmutableMap<String, AnnotationValue> values =
        Processors.getAnnotationValues(elements, annotationMirror);

    return new AutoValue_AggregatedDepsMetadata(
        getTestElement(values.get("test"), elements),
        getComponents(values.get("components"), elements),
        getDependencyType(
            values.get("modules"),
            values.get("entryPoints"),
            values.get("componentEntryPoints")),
        getDependency(
            values.get("modules"),
            values.get("entryPoints"),
            values.get("componentEntryPoints"),
            elements),
        getReplacedDependencies(values.get("replaces"), elements));
  }

  private static Optional<TypeElement> getTestElement(
      AnnotationValue testValue, Elements elements) {
    checkNotNull(testValue);
    String test = AnnotationValues.getString(testValue);
    return test.isEmpty() ? Optional.empty() : Optional.of(elements.getTypeElement(test));
  }

  private static ImmutableSet<TypeElement> getComponents(
      AnnotationValue componentsValue, Elements elements) {
    checkNotNull(componentsValue);
    ImmutableSet<TypeElement> componentNames =
        AnnotationValues.getAnnotationValues(componentsValue).stream()
            .map(AnnotationValues::getString)
            .map(
                // This is a temporary hack to map the old ApplicationComponent to the new
                // SingletonComponent. Technically, this is only needed for backwards compatibility
                // with libraries using the old processor since new processors should convert to the
                // new SingletonComponent when generating the metadata class.
                componentName ->
                    componentName.contentEquals(
                            "dagger.hilt.android.components.ApplicationComponent")
                        ? ClassNames.SINGLETON_COMPONENT.canonicalName()
                        : componentName)
            .map(elements::getTypeElement)
            .collect(toImmutableSet());
    checkState(!componentNames.isEmpty());
    return componentNames;
  }

  private static DependencyType getDependencyType(
      AnnotationValue modulesValue,
      AnnotationValue entryPointsValue,
      AnnotationValue componentEntryPointsValue) {
    checkNotNull(modulesValue);
    checkNotNull(entryPointsValue);
    checkNotNull(componentEntryPointsValue);

    ImmutableSet.Builder<DependencyType> dependencyTypes = ImmutableSet.builder();
    if (!AnnotationValues.getAnnotationValues(modulesValue).isEmpty()) {
      dependencyTypes.add(DependencyType.MODULE);
    }
    if (!AnnotationValues.getAnnotationValues(entryPointsValue).isEmpty()) {
      dependencyTypes.add(DependencyType.ENTRY_POINT);
    }
    if (!AnnotationValues.getAnnotationValues(componentEntryPointsValue).isEmpty()) {
      dependencyTypes.add(DependencyType.COMPONENT_ENTRY_POINT);
    }
    return getOnlyElement(dependencyTypes.build());
  }

  private static TypeElement getDependency(
      AnnotationValue modulesValue,
      AnnotationValue entryPointsValue,
      AnnotationValue componentEntryPointsValue,
      Elements elements) {
    checkNotNull(modulesValue);
    checkNotNull(entryPointsValue);
    checkNotNull(componentEntryPointsValue);

    return elements.getTypeElement(
        AnnotationValues.getString(
            getOnlyElement(
                ImmutableSet.<AnnotationValue>builder()
                    .addAll(AnnotationValues.getAnnotationValues(modulesValue))
                    .addAll(AnnotationValues.getAnnotationValues(entryPointsValue))
                    .addAll(AnnotationValues.getAnnotationValues(componentEntryPointsValue))
                    .build())));
  }

  private static ImmutableSet<TypeElement> getReplacedDependencies(
      AnnotationValue replacedDependenciesValue, Elements elements) {
    // Allow null values to support libraries using a Hilt version before @TestInstallIn was added
    return replacedDependenciesValue == null
        ? ImmutableSet.of()
        : AnnotationValues.getAnnotationValues(replacedDependenciesValue).stream()
            .map(AnnotationValues::getString)
            .map(elements::getTypeElement)
            .map(replacedDep -> getPublicDependency(replacedDep, elements))
            .collect(toImmutableSet());
  }

  /** Returns the public Hilt wrapper module, or the module itself if its already public. */
  private static TypeElement getPublicDependency(TypeElement dependency, Elements elements) {
    return PkgPrivateMetadata.of(elements, dependency, ClassNames.MODULE)
        .map(metadata -> elements.getTypeElement(metadata.generatedClassName().toString()))
        .orElse(dependency);
  }
}
