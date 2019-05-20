/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.heisenberg.extension;

import static java.lang.String.format;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.stream.Collectors.toList;
import static org.mule.runtime.api.meta.ExpressionSupport.NOT_SUPPORTED;
import static org.mule.runtime.api.meta.model.operation.ExecutionType.CPU_INTENSIVE;
import static org.mule.runtime.api.metadata.TypedValue.of;
import static org.mule.runtime.extension.api.annotation.param.MediaType.ANY;
import static org.mule.runtime.extension.api.annotation.param.MediaType.TEXT_PLAIN;
import static org.mule.runtime.extension.api.annotation.param.Optional.PAYLOAD;
import static org.mule.runtime.extension.api.client.DefaultOperationParameters.builder;
import static org.mule.test.heisenberg.extension.HeisenbergExtension.HEISENBERG;
import static org.mule.test.heisenberg.extension.HeisenbergNotificationAction.KNOCKED_DOOR;
import static org.mule.test.heisenberg.extension.HeisenbergNotificationAction.KNOCKING_DOOR;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.api.metadata.DataType;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.core.api.util.IOUtils;
import org.mule.runtime.extension.api.annotation.Alias;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.Ignore;
import org.mule.runtime.extension.api.annotation.OnException;
import org.mule.runtime.extension.api.annotation.Streaming;
import org.mule.runtime.extension.api.annotation.error.Throws;
import org.mule.runtime.extension.api.annotation.execution.Execution;
import org.mule.runtime.extension.api.annotation.metadata.OutputResolver;
import org.mule.runtime.extension.api.annotation.notification.Fires;
import org.mule.runtime.extension.api.annotation.param.Config;
import org.mule.runtime.extension.api.annotation.param.Connection;
import org.mule.runtime.extension.api.annotation.param.Content;
import org.mule.runtime.extension.api.annotation.param.MediaType;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Example;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.param.stereotype.Stereotype;
import org.mule.runtime.extension.api.client.DefaultOperationParameters;
import org.mule.runtime.extension.api.client.DefaultOperationParametersBuilder;
import org.mule.runtime.extension.api.client.ExtensionsClient;
import org.mule.runtime.extension.api.client.OperationParameters;
import org.mule.runtime.extension.api.notification.NotificationEmitter;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.parameter.Literal;
import org.mule.runtime.extension.api.runtime.parameter.ParameterResolver;
import org.mule.runtime.extension.api.runtime.process.CompletionCallback;
import org.mule.test.heisenberg.extension.exception.CureCancerExceptionEnricher;
import org.mule.test.heisenberg.extension.exception.HealthException;
import org.mule.test.heisenberg.extension.exception.HeisenbergException;
import org.mule.test.heisenberg.extension.exception.NullExceptionEnricher;
import org.mule.test.heisenberg.extension.model.BarberPreferences;
import org.mule.test.heisenberg.extension.model.HealthStatus;
import org.mule.test.heisenberg.extension.model.Investment;
import org.mule.test.heisenberg.extension.model.KillParameters;
import org.mule.test.heisenberg.extension.model.KnockeableDoor;
import org.mule.test.heisenberg.extension.model.PersonalInfo;
import org.mule.test.heisenberg.extension.model.RecursiveChainA;
import org.mule.test.heisenberg.extension.model.RecursiveChainB;
import org.mule.test.heisenberg.extension.model.RecursivePojo;
import org.mule.test.heisenberg.extension.model.SaleInfo;
import org.mule.test.heisenberg.extension.model.SimpleKnockeableDoor;
import org.mule.test.heisenberg.extension.model.Weapon;
import org.mule.test.heisenberg.extension.model.types.IntegerAttributes;
import org.mule.test.heisenberg.extension.stereotypes.EmpireStereotype;
import org.mule.test.heisenberg.extension.stereotypes.KillingStereotype;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;

import com.google.common.collect.ImmutableMap;
import javax.inject.Inject;


@Stereotype(EmpireStereotype.class)
public class HeisenbergOperations implements Disposable {

  public static final String CURE_CANCER_MESSAGE = "Can't help you, you are going to die";
  public static final String CALL_GUS_MESSAGE = "You are not allowed to speak with gus.";
  public static final String KILL_WITH_GROUP = "KillGroup";

  public static final String OPERATION_WITH_DISPLAY_NAME_PARAMETER = "resolverEcho";
  public static final String OPERATION_WITH_SUMMARY = "knockMany";
  public static final String OPERATION_WITH_EXAMPLE = "alias";
  public static final String OPERATION_PARAMETER_ORIGINAL_OVERRIDED_DISPLAY_NAME = "literalExpression";
  public static final String OPERATION_PARAMETER_OVERRIDED_DISPLAY_NAME = "Custom overrided display name";
  public static final String KNOCKEABLE_DOORS_SUMMARY = "List of Knockeable Doors";
  public static final String DOOR_PARAMETER = "doors";
  public static final String GREETING_PARAMETER = "greeting";
  public static final String OPERATION_PARAMETER_EXAMPLE = "Hello my friend!";

  public static boolean disposed = false;

  @Inject
  private ExtensionManager extensionManager;

  @Streaming
  @MediaType(value = ANY, strict = false)
  public String sayMyName(@Config HeisenbergExtension config) {
    return config.getPersonalInfo().getName();
  }

  public void die(@Config HeisenbergExtension config) {
    config.setEndingHealth(HealthStatus.DEAD);
  }

  @MediaType(TEXT_PLAIN)
  public Result<String, IntegerAttributes> getEnemy(@Config HeisenbergExtension config,
                                                    @Optional(defaultValue = "0") int index) {
    Charset lastSupportedEncoding = Charset.availableCharsets().values().stream().reduce((first, last) -> last).get();
    org.mule.runtime.api.metadata.DataType dt =
        DataType.builder().type(String.class).mediaType("dead/dead").charset(lastSupportedEncoding.toString()).build();

    return Result.<String, IntegerAttributes>builder().output(config.getEnemies().get(index))
        .mediaType(dt.getMediaType()).attributes(new IntegerAttributes(index)).build();
  }

  public List<Result<String, IntegerAttributes>> getAllEnemies(@Config HeisenbergExtension config) {
    List<Result<String, IntegerAttributes>> enemies = new ArrayList<>(config.getEnemies().size());
    for (int i = 0; i < config.getEnemies().size(); i++) {
      enemies.add(Result.<String, IntegerAttributes>builder()
          .output(config.getEnemies().get(i))
          .attributes(new IntegerAttributes(i))
          .build());
    }

    return enemies;
  }

  @MediaType(TEXT_PLAIN)
  public String echoStaticMessage(@Expression(NOT_SUPPORTED) String message) {
    return message;
  }

  @MediaType(TEXT_PLAIN)
  public String echoWithSignature(@Optional String message) {
    return (message == null ? "" : message) + " echoed by Heisenberg";
  }

  @MediaType(TEXT_PLAIN)
  public String executeForeingOrders(String extensionName, String operationName, @Optional String configName,
                                     ExtensionsClient extensionsClient, Map<String, Object> operationParameters)
      throws MuleException {
    Object output =
        extensionsClient.execute(extensionName, operationName, createOperationParameters(configName, operationParameters))
            .getOutput();
    return output instanceof TypedValue ? (String) ((TypedValue) output).getValue() : (String) output;
  }

  private OperationParameters createOperationParameters(String configName, Map<String, Object> operationParameters) {
    DefaultOperationParametersBuilder builder = DefaultOperationParameters.builder();
    if (configName != null) {
      builder.configName(configName);
    }
    operationParameters.forEach((key, value) -> builder.addParameter(key, value));
    return builder.build();
  }

  @OutputResolver(output = TucoMetadataResolver.class)
  @MediaType(strict = false)
  public String colorizeMeth() {
    return "Blue";
  }

  // This operation will only pass the validation in the MediaTypeModelValidator in runtime
  @OutputResolver(output = TucoMetadataResolver.class)
  @MediaType(value = TEXT_PLAIN, strict = false)
  public String callDea() {
    return "Help DEA!";
  }

  @Stereotype(KillingStereotype.class)
  @MediaType(TEXT_PLAIN)
  public String kill(@Optional(defaultValue = PAYLOAD) String victim, String goodbyeMessage) throws Exception {
    KillParameters killParameters = new KillParameters(victim, goodbyeMessage);
    return format("%s, %s", killParameters.getGoodbyeMessage(), killParameters.getVictim());
  }

  @MediaType(TEXT_PLAIN)
  @Fires(KnockNotificationProvider.class)
  public String knock(KnockeableDoor knockedDoor, NotificationEmitter notificationEmitter) {
    TypedValue<SimpleKnockeableDoor> door = of(new SimpleKnockeableDoor(knockedDoor));
    notificationEmitter.fire(KNOCKING_DOOR, door);
    String knock = knockedDoor.knock();
    notificationEmitter.fire(KNOCKED_DOOR, door);
    return knock;
  }

  @OutputResolver(output = HeisenbergOutputResolver.class)
  public ExtensionManager getInjectedExtensionManager() {
    return extensionManager;
  }

  @MediaType(TEXT_PLAIN)
  public String alias(@Example(OPERATION_PARAMETER_EXAMPLE) String greeting,
                      @ParameterGroup(name = "Personal Info") PersonalInfo info) {
    return String.format("%s, my name is %s and I'm %d years old", greeting, info.getName(), info.getAge());
  }

  public BarberPreferences getBarberPreferences(@Config HeisenbergExtension config) {
    return config.getBarberPreferences();
  }

  public BarberPreferences getInlineInfo(@ParameterGroup(name = "Personal Barber",
      showInDsl = true) @DisplayName("Personal preference") BarberPreferences preferences) {
    return preferences;
  }

  public PersonalInfo getInlinePersonalInfo(@ParameterGroup(name = "Personal Info Argument",
      showInDsl = true) @DisplayName("Personal preference") PersonalInfo info) {
    return info;
  }

  @MediaType(TEXT_PLAIN)
  public String transform(String transformation) {
    return transformation;
  }

  public void disguice(@ParameterGroup(name = "currentLook") @DisplayName("Look") BarberPreferences currentLook,
                       @ParameterGroup(name = "disguise", showInDsl = true) @DisplayName("Look") BarberPreferences disguise) {

  }

  public List<String> knockMany(@Summary(KNOCKEABLE_DOORS_SUMMARY) List<KnockeableDoor> doors) {
    return doors.stream().map(KnockeableDoor::knock).collect(toList());
  }

  @MediaType(TEXT_PLAIN)
  public String callSaul(@Connection HeisenbergConnection connection) {
    return connection.callSaul();
  }

  @MediaType(TEXT_PLAIN)
  public String callGusFring() throws HeisenbergException {
    throw new HeisenbergException(CALL_GUS_MESSAGE);
  }

  @MediaType(TEXT_PLAIN)
  public void callGusFringNonBlocking(CompletionCallback<Void, Void> callback) {
    final ExecutorService executor = newSingleThreadExecutor();

    executor.execute(() -> {
      callback.error(new HeisenbergException(CALL_GUS_MESSAGE));
    });
  }

  @OnException(CureCancerExceptionEnricher.class)
  @Throws(HeisenbergErrorTyperProvider.class)
  @MediaType(TEXT_PLAIN)
  public String cureCancer() throws HealthException {
    throw new HealthException(CURE_CANCER_MESSAGE);
  }

  @Execution(CPU_INTENSIVE)
  public Investment approve(Investment investment,
                            @Optional RecursivePojo recursivePojo,
                            @Optional RecursiveChainB recursiveChainB,
                            @Optional RecursiveChainA recursiveChainA) {
    investment.approve();
    return investment;
  }

  public Map<String, HealthStatus> getMedicalHistory(Map<String, HealthStatus> healthByYear) {
    return healthByYear;
  }

  public HeisenbergConnection getConnection(@Connection HeisenbergConnection connection) {
    return connection;
  }

  @MediaType(TEXT_PLAIN)
  public String getSaulPhone(@Connection HeisenbergConnection connection) {
    return connection.getSaulPhoneNumber();
  }

  @MediaType(TEXT_PLAIN)
  public ParameterResolver<String> resolverEcho(
                                                @DisplayName(OPERATION_PARAMETER_OVERRIDED_DISPLAY_NAME) ParameterResolver<String> literalExpression) {
    return literalExpression;
  }

  @MediaType(TEXT_PLAIN)
  public String literalEcho(Literal<String> literalExpression) {
    return literalExpression.getLiteralValue().orElse(null);
  }

  public int[][] getGramsInStorage(@Optional(defaultValue = PAYLOAD) int[][] grams) {
    return grams;
  }

  public Map<String, SaleInfo> processSale(Map<String, SaleInfo> sales) {
    return sales;
  }

  @OutputResolver(output = HeisenbergOutputResolver.class)
  public ParameterResolver<Weapon> processWeapon(@Optional ParameterResolver<Weapon> weapon) {
    return weapon;
  }

  @OutputResolver(output = HeisenbergOutputResolver.class)
  public ParameterResolver<List<Weapon>> processWeaponList(@Optional ParameterResolver<List<Weapon>> weapons) {
    return weapons;
  }

  @OutputResolver(output = HeisenbergOutputResolver.class)
  public ParameterResolver<Weapon> processWeaponWithDefaultValue(@Optional(
      defaultValue = "#[payload]") ParameterResolver<Weapon> weapon) {
    return weapon;
  }

  @OutputResolver(output = HeisenbergOutputResolver.class)
  public ParameterResolver<List<Weapon>> processWeaponListWithDefaultValue(@Optional(
      defaultValue = "#[payload]") ParameterResolver<List<Weapon>> weapons) {
    return weapons;
  }

  public ParameterResolver<List<String>> processAddressBook(ParameterResolver<List<String>> phoneNumbers) {
    return phoneNumbers;
  }

  @OnException(NullExceptionEnricher.class)
  public void failToExecute() throws HeisenbergException {
    callGusFring();
  }

  public void storeMoney(ObjectStore<Long> objectStore, Long money) throws Exception {
    objectStore.store("money", money);
  }

  @Ignore
  public void ignoredOperation() {

  }

  @OutputResolver(output = HeisenbergOutputResolver.class)
  public Map<String, Weapon> byPassWeapon(@Alias("awesomeWeapon") Weapon weapon, @Alias("awesomeName") String name) {
    return ImmutableMap.of(name, weapon);
  }

  @Alias("echo")
  @MediaType(TEXT_PLAIN)
  public ParameterResolver<String> resolverEchoWithAlias(
                                                         @DisplayName(OPERATION_PARAMETER_OVERRIDED_DISPLAY_NAME) ParameterResolver<String> literalExpression) {
    return literalExpression;
  }

  @MediaType(TEXT_PLAIN)
  public String operationWithInputStreamContentParam(@ParameterGroup(name = "Test",
      showInDsl = true) InputStreamParameterGroup isGroup) {
    return IOUtils.toString(isGroup.getInputStreamContent());
  }

  public void throwError() {
    throw new LinkageError();
  }


  @MediaType(value = TEXT_PLAIN, strict = false)
  public InputStream nameAsStream(@Config HeisenbergExtension config) {
    return new ByteArrayInputStream(sayMyName(config).getBytes());
  }

  @Override
  public void dispose() {
    disposed = true;
  }

  @MediaType(TEXT_PLAIN)
  public String executeKillWithClient(String configName, ExtensionsClient client) {
    OperationParameters params = builder().configName(configName)
        .addParameter("victim", "Juani")
        .addParameter("goodbyeMessage", "ADIOS")
        .build();
    try {
      return (String) client.execute(HEISENBERG, "kill", params).getOutput();
    } catch (MuleException e) {
      throw new RuntimeException(e);
    }
  }

  @MediaType(TEXT_PLAIN)
  public String executeRemoteKill(String extension, String configName, String operation,
                                  @Content Map<String, String> parameters,
                                  ExtensionsClient client) {
    DefaultOperationParametersBuilder paramsBuilder = builder().configName(configName);
    for (Entry<String, String> param : parameters.entrySet()) {
      paramsBuilder = paramsBuilder.addParameter(param.getKey(), param.getValue());
    }

    try {
      client.execute(extension, operation, paramsBuilder.build()).getOutput();
      return "Now he sleeps with the fishes.";
    } catch (MuleException e) {
      throw new RuntimeException(e);
    }
  }

  public void blockingNonBlocking(CompletionCallback<Void, Void> completionCallback) {}
}