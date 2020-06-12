/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.data;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Optional.empty;
import static java.util.stream.Collectors.toList;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.value.ResolvingFailure.Builder.newFailure;
import static org.mule.runtime.core.internal.event.NullEventFactory.getNullEvent;
import static org.mule.runtime.core.api.util.ClassUtils.withContextClassLoader;
import static org.mule.runtime.extension.api.metadata.NullMetadataResolver.NULL_CATEGORY_NAME;
import static org.mule.runtime.module.extension.internal.util.MuleExtensionUtils.getClassLoader;
import static org.mule.runtime.module.tooling.internal.data.DefaultDataProviderResult.success;
import static org.mule.runtime.module.tooling.internal.data.DefaultDataValue.fromKeys;
import static org.mule.runtime.module.tooling.internal.data.DefaultDataValue.fromValues;
import static org.mule.runtime.module.tooling.internal.data.NoOpMetadataCache.getNoOpCache;
import static org.mule.runtime.module.tooling.internal.data.ParameterExtractor.extractValue;
import org.mule.runtime.api.component.Component;
import org.mule.metadata.internal.utils.MetadataTypeWriter;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.HasOutputModel;
import org.mule.runtime.api.meta.model.OutputModel;
import org.mule.runtime.api.meta.model.config.ConfigurationModel;
import org.mule.runtime.api.meta.model.parameter.ParameterizedModel;
import org.mule.runtime.api.metadata.MetadataContext;
import org.mule.runtime.api.metadata.MetadataKey;
import org.mule.runtime.api.metadata.MetadataKeysContainer;
import org.mule.runtime.api.metadata.descriptor.ComponentMetadataDescriptor;
import org.mule.runtime.api.metadata.resolving.MetadataResult;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.api.util.Reference;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.app.declaration.api.ComponentElementDeclaration;
import org.mule.runtime.app.declaration.api.ParameterValue;
import org.mule.runtime.app.declaration.api.ParameterValueVisitor;
import org.mule.runtime.app.declaration.api.fluent.ParameterListValue;
import org.mule.runtime.app.declaration.api.fluent.ParameterObjectValue;
import org.mule.runtime.app.declaration.api.fluent.ParameterSimpleValue;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.ConfigurationException;
import org.mule.runtime.core.api.connector.ConnectionManager;
import org.mule.runtime.core.api.el.ExpressionManager;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.core.api.transaction.TransactionConfig;
import org.mule.runtime.extension.api.declaration.type.ExtensionsTypeLoaderFactory;
import org.mule.runtime.extension.api.property.ClassLoaderModelProperty;
import org.mule.runtime.extension.api.property.MetadataKeyIdModelProperty;
import org.mule.runtime.extension.api.property.TypeResolversInformationModelProperty;
import org.mule.runtime.extension.api.runtime.config.ConfigurationInstance;
import org.mule.runtime.extension.api.runtime.operation.ExecutionContext;
import org.mule.runtime.extension.api.values.ValueResolvingException;
import org.mule.runtime.module.extension.internal.loader.java.property.ValueProviderFactoryModelProperty;
import org.mule.runtime.module.extension.internal.metadata.DefaultMetadataContext;
import org.mule.runtime.module.extension.internal.metadata.MetadataMediator;
import org.mule.runtime.module.extension.internal.runtime.DefaultExecutionContext;
import org.mule.runtime.module.extension.internal.runtime.config.ResolverSetBasedParameterResolver;
import org.mule.runtime.module.extension.internal.runtime.operation.OperationParameterValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ParameterValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ParametersResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ResolverSet;
import org.mule.runtime.module.extension.internal.runtime.resolver.StaticValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ValueResolver;
import org.mule.runtime.module.extension.internal.util.ReflectionCache;
import org.mule.runtime.module.extension.internal.value.ValueProviderMediator;
import org.mule.runtime.module.tooling.api.data.DataProviderResult;
import org.mule.runtime.module.tooling.api.data.DataProviderService;
import org.mule.runtime.module.tooling.api.data.DataResult;
import org.mule.runtime.module.tooling.internal.utils.ArtifactHelper;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.apache.commons.lang3.builder.ToStringBuilder;

public class InternalDataProviderService implements DataProviderService {

  @Inject
  private ConfigurationComponentLocator componentLocator;

  @Inject
  private ExtensionManager extensionManager;

  @Inject
  private ReflectionCache reflectionCache;

  @Inject
  private MuleContext muleContext;

  @Inject
  private ConnectionManager connectionManager;

  @Inject
  private ExpressionManager expressionManager;

  private LazyValue<ArtifactHelper> artifactHelperLazyValue;

  InternalDataProviderService(ArtifactDeclaration artifactDeclaration) {
    this.artifactHelperLazyValue =
        new LazyValue<>(() -> new ArtifactHelper(extensionManager, componentLocator, artifactDeclaration));
  }

  private ArtifactHelper artifactHelper() {
    return artifactHelperLazyValue.get();
  }

  @Override
  public DataProviderResult<List<DataResult>> discover() {
    return success(artifactHelper().findConfigurationModel()
        .map(this::discoverValuesFromConfigModel)
        .orElse(emptyList()));
  }

  @Override
  public DataProviderResult<DataResult> getValues(ComponentElementDeclaration component, String parameterName) {
    return success(artifactHelper().findComponentModel(component)
        .map(cm -> discoverParameterValues(cm, parameterName, parameterValueResolver(component, cm)))
        .orElse(new DefaultDataResult(parameterName, emptySet())));
  }

  private List<DataResult> discoverValuesFromConfigModel(ConfigurationModel configurationModel) {
    final List<DataResult> results = new LinkedList<>();
    configurationModel.getOperationModels().stream().map(this::getResultsForModel).forEach(results::addAll);
    configurationModel.getSourceModels().stream().map(this::getResultsForModel).forEach(results::addAll);
    return results;
  }

  private <T extends ComponentModel> DataResult discoverParameterValues(T componentModel,
                                                                        String parameterName,
                                                                        ParameterValueResolver parameterValueResolver) {

    return componentModel
        .getModelProperty(MetadataKeyIdModelProperty.class)
        .map(mp -> Objects.equals(mp.getParameterName(), parameterName) ? mp : null)
        .map(mp -> resolveMetadataKeys(componentModel, s -> mp.getCategoryName().map(cn -> cn.equals(s)).orElse(false)))
        .map(r -> r.get(0))
        .orElseGet(() -> {
          ValueProviderMediator<T> valueProviderMediator = createValueProviderMediator(componentModel);
          final String resolveName = getResolverName(componentModel, parameterName);
          try {
            return new DefaultDataResult(resolveName,
                                         fromValues(valueProviderMediator.getValues(parameterName,
                                                                                    parameterValueResolver,
                                                                                    connectionSupplier(),
                                                                                    () -> null)));
          } catch (ValueResolvingException e) {
            return new DefaultDataResult(resolveName,
                                         newFailure(e).build());
          }
        });

  }

  private <T extends ComponentModel> List<DataResult> getResultsForModel(T model) {
    final List<DataResult> results = new LinkedList<>();
    results.addAll(resolveValues(model));
    results.addAll(resolveMetadataKeys(model, s -> true));
    return results;
  }

  private <T extends ComponentModel> void resolveMetadataType(T model, MetadataKeysContainer metadataKeysContainer) {
    if (model instanceof HasOutputModel) {
      OutputModel outputModel = ((HasOutputModel) model).getOutput();
      if (outputModel.hasDynamicType()) {// or if outputAttributes is dynamic, or any of its input parameters is dynamic
        model.getModelProperty(TypeResolversInformationModelProperty.class).ifPresent(typeResolversInformationModelProperty -> {
          MetadataMediator<T> metadataMediator = new MetadataMediator<>(model);
          Set<MetadataKey> metadataKeys = metadataKeysContainer.getKeysByCategory().get(typeResolversInformationModelProperty.getCategoryName());
          metadataKeys.forEach(key -> {
            ClassLoader extensionClassLoader = getExtensionClassLoader();
            withContextClassLoader(extensionClassLoader, () -> {
              MetadataResult<ComponentMetadataDescriptor<T>> metadata = metadataMediator.getMetadata(createMetadataContext(), key);
              if (metadata.isSuccess()) {
                System.out.println(ToStringBuilder.reflectionToString(metadata.get().getMetadataAttributes()));
                System.out.println(new MetadataTypeWriter().toString(((HasOutputModel) metadata.get().getModel()).getOutput().getType()));
              } else {
                System.err.println("error while resolving metadata for key: " + key + " " + metadata.getFailures());
              }
            });
          });
        });
      }
    }

  }

  //TODO improve this!
  private ClassLoader getExtensionClassLoader() {
    return artifactHelper().getExtensionModel(artifactHelper().findConfigurationDeclaration().get()).getModelProperty(ClassLoaderModelProperty.class).get().getClassLoader();
  }

  private <T extends ComponentModel> List<DataResult> resolveValues(T model) {
    return model.getAllParameterModels()
        .stream()
        .filter(pm -> pm.getValueProviderModel().isPresent())
        .map(vpp -> discoverParameterValues(model, vpp.getName(), null))
        .collect(toList());
  }

  private <T extends ComponentModel> List<DataResult> resolveMetadataKeys(T model, Predicate<String> categoryFilter) {
    MetadataMediator<T> metadataMediator = new MetadataMediator<>(model);
    MetadataResult<MetadataKeysContainer> keysResult = metadataMediator.getMetadataKeys(createMetadataContext(), reflectionCache);

    if (keysResult.isSuccess()) {
      resolveMetadataType(model, keysResult.get());
    }

    return keysResult.get()
        .getKeysByCategory()
        .entrySet()
        .stream()
        .filter(e -> categoryFilter.test(e.getKey()))
        .filter(e -> !e.getKey().equals(NULL_CATEGORY_NAME))
        .map((e -> new DefaultDataResult(e.getKey(), fromKeys(e.getValue()))))
        .collect(toList());
  }

  private <T extends ComponentModel> ValueProviderMediator<T> createValueProviderMediator(T constructModel) {
    return new ValueProviderMediator<>(constructModel,
                                       () -> muleContext,
                                       () -> reflectionCache);
  }

  private String getResolverName(ParameterizedModel componentModel, String parameterName) {
    return componentModel
        .getAllParameterModels()
        .stream()
        .filter(pm -> Objects.equals(pm.getName(), parameterName)
            && pm.getModelProperty(ValueProviderFactoryModelProperty.class).isPresent())
        .findAny()
        .flatMap(pm -> pm.getModelProperty(ValueProviderFactoryModelProperty.class))
        .map(mp -> mp.getValueProvider().getSimpleName())
        .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not find parameter with name: %s",
                                                                        parameterName)));
  }

  private Supplier<Object> connectionSupplier() {
    return artifactHelper().getConnectionInstance()
        .map(ci -> (Supplier<Object>) () -> ci)
        .orElse(() -> null);

  }

  private MetadataContext createMetadataContext() {
    Supplier<Optional<ConfigurationInstance>> configSupplier = () -> artifactHelper().getConfigurationInstance();
    return artifactHelper().findConfigurationDeclaration().map(
                                                               cd -> new DefaultMetadataContext(configSupplier,
                                                                                                connectionManager,
                                                                                                getNoOpCache(),
                                                                                                ExtensionsTypeLoaderFactory
                                                                                                    .getDefault()
                                                                                                    .createTypeLoader(getClassLoader(artifactHelper()
                                                                                                        .getExtensionModel(cd)))))
        .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not create MetadataContext")));
  }

  private <T extends ComponentModel> ParameterValueResolver parameterValueResolver(ComponentElementDeclaration componentElementDeclaration,
                                                                                   T model) {
    Map<String, Object> parametersMap = new HashMap<>();

    componentElementDeclaration
        .getParameterGroups()
        .forEach(
                 parameterGroup -> parameterGroup
                     .getParameters()
                     .forEach(
                              p -> parametersMap.put(p.getName(), extractValue(p.getValue()))));

    try {
      final ResolverSet resolverSet =
          ParametersResolver.fromValues(parametersMap, muleContext, false, reflectionCache, expressionManager)
              .getParametersAsResolverSet(model, muleContext);
      return new ResolverSetBasedParameterResolver(resolverSet, model, reflectionCache, expressionManager);
    } catch (ConfigurationException e) {
      throw new MuleRuntimeException(e);
    }
  }

}
