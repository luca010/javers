package org.javers.core;

import com.google.gson.TypeAdapter;
import org.javers.common.collections.Lists;
import org.javers.common.date.DateProvider;
import org.javers.common.date.DefaultDateProvider;
import org.javers.common.validation.Validate;
import org.javers.core.JaversCoreProperties.PrettyPrintDateFormats;
import org.javers.core.commit.Commit;
import org.javers.core.commit.CommitFactoryModule;
import org.javers.core.commit.CommitId;
import org.javers.core.diff.Diff;
import org.javers.core.diff.DiffFactoryModule;
import org.javers.core.diff.ListCompareAlgorithm;
import org.javers.core.diff.appenders.DiffAppendersModule;
import org.javers.core.diff.custom.*;
import org.javers.core.graph.GraphFactoryModule;
import org.javers.core.graph.ObjectAccessHook;
import org.javers.core.graph.TailoredJaversMemberFactoryModule;
import org.javers.core.json.JsonAdvancedTypeAdapter;
import org.javers.core.json.JsonConverter;
import org.javers.core.json.JsonConverterBuilder;
import org.javers.core.json.JsonTypeAdapter;
import org.javers.core.json.typeadapter.change.ChangeTypeAdaptersModule;
import org.javers.core.json.typeadapter.commit.CommitTypeAdaptersModule;
import org.javers.core.json.typeadapter.commit.DiffTypeDeserializer;
import org.javers.core.metamodel.annotation.*;
import org.javers.core.metamodel.clazz.*;
import org.javers.core.metamodel.scanner.ScannerModule;
import org.javers.core.metamodel.type.*;
import org.javers.core.pico.AddOnsModule;
import org.javers.core.snapshot.SnapshotModule;
import org.javers.groovysupport.GroovyAddOns;
import org.javers.guava.GuavaAddOns;
import org.javers.jodasupport.JodaAddOns;
import org.javers.mongosupport.MongoLong64JsonDeserializer;
import org.javers.mongosupport.RequiredMongoSupportPredicate;
import org.javers.repository.api.ConfigurationAware;
import org.javers.repository.api.JaversExtendedRepository;
import org.javers.repository.api.JaversRepository;
import org.javers.repository.inmemory.InMemoryRepository;
import org.javers.repository.jql.JqlModule;
import org.javers.shadow.ShadowModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.javers.common.reflection.ReflectionUtil.findClasses;
import static org.javers.common.reflection.ReflectionUtil.isClassPresent;
import static org.javers.common.validation.Validate.argumentIsNotNull;
import static org.javers.common.validation.Validate.argumentsAreNotNull;

/**
 * Creates a JaVers instance based on your domain model metadata and custom configuration.
 * <br/><br/>
 *
 * For example, to build a JaVers instance configured with reasonable defaults:
 * <pre>
 * Javers javers = JaversBuilder.javers().build();
 * </pre>
 *
 * To build a JaVers instance with Entity type registered:
 * <pre>
 * Javers javers = JaversBuilder.javers()
 *                              .registerEntity(MyEntity.class)
 *                              .build();
 * </pre>
 *
 * @see <a href="http://javers.org/documentation/domain-configuration/">http://javers.org/documentation/domain-configuration</a>
 * @author bartosz walacik
 */
public class JaversBuilder extends AbstractContainerBuilder {
    public static final Logger logger = LoggerFactory.getLogger(JaversBuilder.class);

    private final Map<Class, ClientsClassDefinition> clientsClassDefinitions = new LinkedHashMap<>();

    private final Map<Class, Function<Object, String>> mappedToStringFunction = new ConcurrentHashMap<>();

    private final Set<Class> classesToScan = new HashSet<>();

    private final Set<ConditionalTypesPlugin> conditionalTypesPlugins;

    private JaversRepository repository;
    private DateProvider dateProvider;
    private long bootStart = System.currentTimeMillis();

    public static JaversBuilder javers() {
        return new JaversBuilder();
    }

    /**
     * use static factory method {@link JaversBuilder#javers()}
     */
    protected JaversBuilder() {
        logger.debug("starting up JaVers ...");

        //conditional plugins
        conditionalTypesPlugins = new HashSet<>();

        if (isClassPresent("groovy.lang.MetaClass")) {
            conditionalTypesPlugins.add(new GroovyAddOns());
        }
        if (isClassPresent("org.joda.time.LocalDate")){
            conditionalTypesPlugins.add(new JodaAddOns());
        }
        if (isClassPresent("com.google.common.collect.Multimap")) {
            conditionalTypesPlugins.add(new GuavaAddOns());
        }

        // bootstrap phase 1: container & core
        bootContainer();
        addModule(new CoreJaversModule(getContainer()));
    }

    public Javers build() {

        Javers javers = assembleJaversInstance();
        repository.ensureSchema();

        long boot = System.currentTimeMillis() - bootStart;
        logger.info("JaVers instance started in {} ms", boot);
        return javers;
    }

    protected Javers assembleJaversInstance(){
        // bootstrap phase 2: main modules
        addModule(new DiffFactoryModule());
        addModule(new CommitFactoryModule(getContainer()));
        addModule(new SnapshotModule(getContainer()));
        addModule(new GraphFactoryModule(getContainer()));
        addModule(new DiffAppendersModule(coreConfiguration(), getContainer()));
        addModule(new TailoredJaversMemberFactoryModule(coreConfiguration(), getContainer()));
        addModule(new ScannerModule(coreConfiguration(), getContainer()));
        addModule(new ShadowModule(getContainer()));
        addModule(new JqlModule(getContainer()));

        // bootstrap phase 3: add-on modules
        Set<JaversType> additionalTypes = bootAddOns();

        // bootstrap phase 4: TypeMapper
        bootManagedTypeModule();

        // bootstrap phase 5: JSON beans & domain aware typeAdapters
        additionalTypes.addAll( bootJsonConverter() );

        bootDateTimeProvider();

        // clases to scan & additionalTypes
        for (Class c : classesToScan){
            typeMapper().getJaversType(c);
        }
        typeMapper().addPluginTypes(additionalTypes);

        bootRepository();

        return getContainerComponent(JaversCore.class);
    }

    /**
     * @see <a href="http://javers.org/documentation/repository-configuration">http://javers.org/documentation/repository-configuration</a>
     */
    public JaversBuilder registerJaversRepository(JaversRepository repository) {
        argumentsAreNotNull(repository);
        this.repository = repository;
        return this;
    }

    /**
     * Registers an {@link EntityType}. <br/>
     * Use @Id annotation to mark exactly one Id-property.
     * <br/><br/>
     *
     * Optionally, use @Transient or @{@link DiffIgnore} annotations to mark ignored properties.
     * <br/><br/>
     *
     * For example, Entities are: Person, Document
     *
     * @see <a href="http://javers.org/documentation/domain-configuration/#entity">http://javers.org/documentation/domain-configuration/#entity</a>
     * @see #registerEntity(EntityDefinition)
     */
    public JaversBuilder registerEntity(Class<?> entityClass) {
        argumentIsNotNull(entityClass);
        return registerEntity( new EntityDefinition(entityClass));
    }

    /**
     * Registers a {@link ValueObjectType}. <br/>
     * Optionally, use @Transient or @{@link DiffIgnore} annotations to mark ignored properties.
     * <br/><br/>
     *
     * For example, ValueObjects are: Address, Point
     *
     * @see <a href="http://javers.org/documentation/domain-configuration/#value-object">http://javers.org/documentation/domain-configuration/#value-object</a>
     * @see #registerValueObject(ValueObjectDefinition)
     */
    public JaversBuilder registerValueObject(Class<?> valueObjectClass) {
        argumentIsNotNull(valueObjectClass);
        registerType(new ValueObjectDefinition(valueObjectClass));
        return this;
    }

    /**
     * Registers an {@link EntityType}. <br/>
     * Use this method if you are not willing to use {@link Entity} annotation.
     * <br/></br/>
     *
     * Recommended way to create {@link EntityDefinition} is {@link EntityDefinitionBuilder},
     * for example:
     * <pre>
     * javersBuilder.registerEntity(
     *     EntityDefinitionBuilder.entityDefinition(Person.class)
     *     .withIdPropertyName("id")
     *     .withTypeName("Person")
     *     .withIgnoredProperties("notImportantProperty","transientProperty")
     *     .build());
     * </pre>
     *
     * For simple cases, you can use {@link EntityDefinition} constructors,
     * for example:
     * <pre>
     * javersBuilder.registerEntity( new EntityDefinition(Person.class, "login") );
     * </pre>
     *
     * @see <a href="http://javers.org/documentation/domain-configuration/#entity">http://javers.org/documentation/domain-configuration/#entity</a>
     * @see EntityDefinitionBuilder#entityDefinition(Class)
     */
    public JaversBuilder registerEntity(EntityDefinition entityDefinition){
        argumentIsNotNull(entityDefinition);
        return registerType(entityDefinition);
    }

    /**
     * Generic version of {@link #registerEntity(EntityDefinition)} and
     * {@link #registerValueObject(ValueObjectDefinition)}
     */
    public JaversBuilder registerType(ClientsClassDefinition clientsClassDefinition) {
        argumentIsNotNull(clientsClassDefinition);
        clientsClassDefinitions.put(clientsClassDefinition.getBaseJavaClass(), clientsClassDefinition);
        return this;
    }

    /**
     * Registers a {@link ValueObjectType}. <br/>
     * Use this method if you are not willing to use {@link ValueObject} annotations.
     * <br/></br/>
     *
     * Recommended way to create {@link ValueObjectDefinition} is {@link ValueObjectDefinitionBuilder}.
     * For example:
     * <pre>
     * javersBuilder.registerValueObject(ValueObjectDefinitionBuilder.valueObjectDefinition(Address.class)
     *     .withIgnoredProperties(ignoredProperties)
     *     .withTypeName(typeName)
     *     .build();
     * </pre>
     *
     * For simple cases, you can use {@link ValueObjectDefinition} constructors,
     * for example:
     * <pre>
     * javersBuilder.registerValueObject( new ValueObjectDefinition(Address.class, "ignored") );
     * </pre>
     *
     * @see <a href="http://javers.org/documentation/domain-configuration/#value-object">http://javers.org/documentation/domain-configuration/#value-object</a>
     * @see ValueObjectDefinitionBuilder#valueObjectDefinition(Class)
     */
    public JaversBuilder registerValueObject(ValueObjectDefinition valueObjectDefinition) {
        argumentIsNotNull(valueObjectDefinition);
        registerType(valueObjectDefinition);
        return this;
    }

    /**
     * Comma separated list of packages scanned by Javers in search of
     * your classes with the {@link TypeName} annotation.
     * <br/><br/>
     *
     * It's <b>important</b> to declare here all of your packages containing classes with {@literal @}TypeName,<br/>
     * because Javers needs <i>live</i> class definitions to properly deserialize Snapshots from {@link JaversRepository}.
     * <br/><br/>
     *
     * <b>For example</b>, consider this class:
     *
     * <pre>
     * {@literal @}Entity
     * {@literal @}TypeName("Person")
     *  class Person {
     *     {@literal @}Id
     *      private int id;
     *      private String name;
     *  }
     * </pre>
     *
     * In the scenario when Javers reads a Snapshot of type named 'Person'
     * before having a chance to map the Person class definition,
     * the 'Person' type will be mapped to generic {@link UnknownType}.
     * <br/><br/>
     *
     * Since 5.8.4, Javers logs <code>WARNING</code> when UnknownType is created
     * because Snapshots with UnknownType can't be properly deserialized from {@link JaversRepository}.
     *
     * @param packagesToScan e.g. "my.company.domain.person, my.company.domain.finance"
     * @since 2.3
     */
    public JaversBuilder withPackagesToScan(String packagesToScan) {
        if (packagesToScan == null || packagesToScan.trim().isEmpty()) {
            return this;
        }

        long start = System.currentTimeMillis();
        logger.info("scanning package(s): {}", packagesToScan);
        List<Class<?>> scan = findClasses(TypeName.class, packagesToScan.replaceAll(" ","").split(","));
		for (Class<?> c : scan) {
			scanTypeName(c);
		}
		long delta = System.currentTimeMillis() - start;
        logger.info("  found {} ManagedClasse(s) with @TypeName in {} ms", scan.size(), delta);

		return this;
    }

    /**
     * Register your class with &#64;{@link TypeName} annotation
     * in order to use it in all kinds of JQL queries.
     * <br/><br/>
     *
     * You can also use {@link #withPackagesToScan(String)}
     * to scan all your classes.
     * <br/><br/>
     *
     * Technically, this method is the convenient alias for {@link Javers#getTypeMapping(Type)}
     *
     * @since 1.4
     */
    public JaversBuilder scanTypeName(Class userType){
        classesToScan.add(userType);
        return this;
    }

    /**
     * Registers a simple value type (see {@link ValueType}).
     * <br/><br/>
     *
     * For example, values are: BigDecimal, LocalDateTime.
     * <br/><br/>
     *
     * Use this method if can't use the {@link Value} annotation.
     * <br/><br/>
     *
     * By default, Values are compared using {@link Object#equals(Object)}.
     * You can provide external <code>equals()</code> function
     * by registering a {@link CustomValueComparator}.
     * See {@link #registerValue(Class, CustomValueComparator)}.
     *
     * @see <a href="http://javers.org/documentation/domain-configuration/#ValueType">http://javers.org/documentation/domain-configuration/#ValueType</a>
     */
    public JaversBuilder registerValue(Class<?> valueClass) {
        argumentIsNotNull(valueClass);
        registerType(new ValueDefinition(valueClass));
        return this;
    }

    /**
     * Registers a {@link ValueType} with a custom comparator to be used instead of
     * {@link Object#equals(Object)}.
     * <br/><br/>
     *
     * For example, by default, BigDecimals are Values
     * compared using {@link java.math.BigDecimal#equals(Object)},
     * sadly it isn't the correct mathematical equality:
     *
     * <pre>
     *     new BigDecimal("1.000").equals(new BigDecimal("1.00")) == false
     * </pre>
     *
     * If you want to compare them in the right way &mdash; ignoring trailing zeros &mdash;
     * register this comparator:
     *
     * <pre>
     * JaversBuilder.javers()
     *     .registerValue(BigDecimal.class, new BigDecimalComparatorWithFixedEquals())
     *     .build();
     * </pre>
     *
     * @param <T> Value Type
     * @see <a href="http://javers.org/documentation/domain-configuration/#ValueType">http://javers.org/documentation/domain-configuration/#ValueType</a>
     * @see <a href="https://javers.org/documentation/diff-configuration/#custom-comparators">https://javers.org/documentation/diff-configuration/#custom-comparators</a>
     * @see BigDecimalComparatorWithFixedEquals
     * @see CustomBigDecimalComparator
     * @since 3.3
     */
    public <T> JaversBuilder registerValue(Class<T> valueClass, CustomValueComparator<T> customValueComparator) {
        argumentsAreNotNull(valueClass, customValueComparator);

        if (!clientsClassDefinitions.containsKey(valueClass)){
            registerType(new ValueDefinition(valueClass));
        }
        ValueDefinition def = getClassDefinition(valueClass);
        def.setCustomValueComparator(customValueComparator);

        return this;
    }

    /**
     * Lambda-style variant of {@link #registerValue(Class, CustomValueComparator)}.
     * <br/><br/>
     *
     * For example, you can register the comparator for BigDecimals with fixed equals:
     *
     * <pre>
     * Javers javers = JaversBuilder.javers()
     *     .registerValue(BigDecimal.class, (a, b) -> a.compareTo(b) == 0,
     *                                           a -> a.stripTrailingZeros().toString())
     *     .build();
     * </pre>
     *
     * @param <T> Value Type
     * @see #registerValue(Class, CustomValueComparator)
     * @since 5.8
     */
    public <T> JaversBuilder registerValue(Class<T> valueClass,
                                           BiFunction<T, T, Boolean> equalsFunction,
                                           Function<T, String> toStringFunction) {
        Validate.argumentsAreNotNull(valueClass, equalsFunction, toStringFunction);

        return registerValue(valueClass, new CustomValueComparator<T>() {
            @Override
            public boolean equals(T a, T b) {
                return equalsFunction.apply(a,b);
            }

            @Override
            public String toString(@Nonnull T value) {
                return toStringFunction.apply(value);
            }
        });
    }

    /**
     * <b>Deprecated</b>, use {@link #registerValue(Class, CustomValueComparator)}.
     *
     * <br/><br/>
     *
     * Since this comparator is not aligned with {@link Object#hashCode()},
     * it calculates incorrect results when a given Value is used in hashing context
     * (when comparing Sets with Values or Maps with Values as keys).
     *
     * @see CustomValueComparator
     */
    @Deprecated
    public <T> JaversBuilder registerValue(Class<T> valueClass, BiFunction<T, T, Boolean> equalsFunction) {
        Validate.argumentsAreNotNull(valueClass, equalsFunction);

        return registerValue(valueClass, new CustomValueComparator<T>() {
            @Override
            public boolean equals(T a, T b) {
                return equalsFunction.apply(a,b);
            }

            @Override
            public String toString(@Nonnull T value) {
                return value.toString();
            }
        });
    }

    /**
     * <b>Deprecated</b>, use {@link #registerValue(Class, CustomValueComparator)}.
     *
     * @see CustomValueComparator
     * @since 3.7.6
     */
    @Deprecated
    public <T> JaversBuilder registerValueWithCustomToString(Class<T> valueClass, Function<T, String> toStringFunction) {
        Validate.argumentsAreNotNull(valueClass, toStringFunction);
        return registerValue(valueClass, (a,b) -> Objects.equals(a,b), toStringFunction);
    }

    /**
     * Marks given class as ignored by JaVers.
     * <br/><br/>
     *
     * Use this method if you are not willing to use {@link DiffIgnore} annotation.
     *
     * @see DiffIgnore
     */
    public JaversBuilder registerIgnoredClass(Class<?> ignoredClass) {
        argumentIsNotNull(ignoredClass);
        registerType(new IgnoredTypeDefinition(ignoredClass));
        return this;
    }

    /**
     * Registers a {@link ValueType} and its custom JSON adapter.
     * <br><br>
     *
     * Useful for not trivial ValueTypes when Gson's default representation isn't appropriate
     *
     * @see <a href="http://javers.org/documentation/repository-configuration/#json-type-adapters">http://javers.org/documentation/repository-configuration/#json-type-adapters</a>
     * @see JsonTypeAdapter
     */
    public JaversBuilder registerValueTypeAdapter(JsonTypeAdapter typeAdapter) {
        for (Class c : (List<Class>)typeAdapter.getValueTypes()){
            registerValue(c);
        }

        jsonConverterBuilder().registerJsonTypeAdapter(typeAdapter);
        return this;
    }

    /**
     * <font color='red'>INCUBATING</font><br/>
     *
     * For complex structures like Multimap
     * @since 3.1
     */
    public JaversBuilder registerJsonAdvancedTypeAdapter(JsonAdvancedTypeAdapter adapter) {
        jsonConverterBuilder().registerJsonAdvancedTypeAdapter(adapter);
        return this;
    }

    /**
     * Registers {@link ValueType} and its custom native
     * <a href="http://code.google.com/p/google-gson/">Gson</a> adapter.
     * <br/><br/>
     *
     * Useful when you already have Gson {@link TypeAdapter}s implemented.
     *
     * @see TypeAdapter
     */
    public JaversBuilder registerValueGsonTypeAdapter(Class valueType, TypeAdapter nativeAdapter) {
        registerValue(valueType);
        jsonConverterBuilder().registerNativeTypeAdapter(valueType, nativeAdapter);
        return this;
    }

    /**
     * Switch on when you need a type safe serialization for
     * heterogeneous collections like List, List&lt;Object&gt;.
     * <br/><br/>
     *
     * Heterogeneous collections are collections which contains items of different types
     * (or types unknown at compile time).
     * <br/><br/>
     *
     * This approach is generally discouraged, prefer statically typed collections
     * with exactly one type of items like List&lt;String&gt;.
     *
     * @see org.javers.core.json.JsonConverterBuilder#typeSafeValues(boolean)
     * @param typeSafeValues default false
     */
    public JaversBuilder withTypeSafeValues(boolean typeSafeValues) {
        jsonConverterBuilder().typeSafeValues(typeSafeValues);
        return this;
    }

    /**
     * choose between JSON pretty or concise printing style, i.e. :
     *
     * <ul><li>pretty:
     * <pre>
     * {
     *     "value": 5
     * }
     * </pre>
     * </li><li>concise:
     * <pre>
     * {"value":5}
     * </pre>
     * </li></ul>
     *
     * @param prettyPrint default true
     */
    public JaversBuilder withPrettyPrint(boolean prettyPrint) {
        jsonConverterBuilder().prettyPrint(prettyPrint);
        return this;
    }

    public JaversBuilder registerEntities(Class<?>... entityClasses) {
        for(Class clazz : entityClasses) {
            registerEntity(clazz);
        }
        return this;
    }

    public JaversBuilder registerValueObjects(Class<?>... valueObjectClasses) {
        for(Class clazz : valueObjectClasses) {
            registerValueObject(clazz);
        }
        return this;
    }

    /**
     * Default style is {@link MappingStyle#FIELD}.
     *
     * @see <a href="http://javers.org/documentation/domain-configuration/#property-mapping-style">http://javers.org/documentation/domain-configuration/#property-mapping-style</a>
     */
    public JaversBuilder withMappingStyle(MappingStyle mappingStyle) {
        argumentIsNotNull(mappingStyle);
        coreConfiguration().withMappingStyle(mappingStyle);
        return this;
    }

    /**
     * <ul>
     * <li/> {@link CommitIdGenerator#SYNCHRONIZED_SEQUENCE} &mdash; for non-distributed applications
     * <li/> {@link CommitIdGenerator#RANDOM} &mdash; for distributed applications
     * </ul>
     * SYNCHRONIZED_SEQUENCE is used by default.
     */
    public JaversBuilder withCommitIdGenerator(CommitIdGenerator commitIdGenerator) {
        coreConfiguration().withCommitIdGenerator(commitIdGenerator);
        return this;
    }

    JaversBuilder withCustomCommitIdGenerator(Supplier<CommitId> commitIdGenerator) {
        coreConfiguration().withCustomCommitIdGenerator(commitIdGenerator);
        return this;
    }

    /**
     * When enabled, {@link Javers#compare(Object oldVersion, Object currentVersion)}
     * generates additional 'Snapshots' of new objects (objects added in currentVersion graph).
     * <br/>
     * For each new object, state of its properties is captured and returned as a Set of PropertyChanges.
     * These Changes have null at the left side and a current property value at the right side.
     * <br/><br/>
     *
     * Disabled by default.
     */
    public JaversBuilder withNewObjectsSnapshot(boolean newObjectsSnapshot){
        coreConfiguration().withNewObjectsSnapshot(newObjectsSnapshot);
        return this;
    }

    public JaversBuilder withObjectAccessHook(ObjectAccessHook objectAccessHook) {
        removeComponent(ObjectAccessHook.class);
        bindComponent(ObjectAccessHook.class, objectAccessHook);
        return this;
    }

    /**
     * Registers a {@link CustomPropertyComparator} for a given class and maps this class
     * to {@link CustomType}.
     * <br/><br/>
     *
     * <b>
     * Custom Types are not easy to manage, use it as a last resort,<br/>
     * only for corner cases like comparing custom Collection types.</b>
     * <br/><br/>
     *
     * In most cases, it's better to customize the Javers' diff algorithm using
     * much more simpler {@link CustomValueComparator},
     * see {@link #registerValue(Class, CustomValueComparator)}.
     *
     * @param <T> Custom Type
     * @see <a href="https://javers.org/documentation/diff-configuration/#custom-comparators">https://javers.org/documentation/diff-configuration/#custom-comparators</a>
     */
    public <T> JaversBuilder registerCustomType(Class<T> customType, CustomPropertyComparator<T, ?> comparator){
        registerType(new CustomDefinition(customType, comparator));
        bindComponent(comparator, new CustomToNativeAppenderAdapter(comparator, customType));
        return this;
    }

    /**
     * @deprecated Renamed to {@link #registerCustomType(Class, CustomPropertyComparator)}
     */
    @Deprecated
    public <T> JaversBuilder registerCustomComparator(CustomPropertyComparator<T, ?> comparator, Class<T> customType){
        return registerCustomType(customType, comparator);
    }

    /**
     * Choose between two algorithms for comparing list: ListCompareAlgorithm.SIMPLE
     * or ListCompareAlgorithm.LEVENSHTEIN_DISTANCE.
     * <br/><br/>
     * Generally, we recommend using LEVENSHTEIN_DISTANCE, because it's smarter.
     * However, it can be slow for long lists, so SIMPLE is enabled by default.
     * <br/><br/>
     *
     * Refer to <a href="http://javers.org/documentation/diff-configuration/#list-algorithms">http://javers.org/documentation/diff-configuration/#list-algorithms</a>
     * for description of both algorithms
     *
     * @param algorithm ListCompareAlgorithm.SIMPLE is used by default
     */
    public JaversBuilder withListCompareAlgorithm(ListCompareAlgorithm algorithm) {
        argumentIsNotNull(algorithm);
        coreConfiguration().withListCompareAlgorithm(algorithm);
        return this;
    }

  /**
   * DateProvider providers current util for {@link Commit#getCommitDate()}.
   * <br/>
   * By default, now() is used.
   * <br/>
   * Overriding default dateProvider probably makes sense only in test environment.
   */
    public JaversBuilder withDateTimeProvider(DateProvider dateProvider) {
        argumentIsNotNull(dateProvider);
        this.dateProvider = dateProvider;
        return this;
    }

    public JaversBuilder withPrettyPrintDateFormats(PrettyPrintDateFormats prettyPrintDateFormats) {
        coreConfiguration().withPrettyPrintDateFormats(prettyPrintDateFormats);
        return this;
    }

    public JaversBuilder withProperties(JaversCoreProperties javersProperties) {
        this.withListCompareAlgorithm(ListCompareAlgorithm.valueOf(javersProperties.getAlgorithm().toUpperCase()))
            .withCommitIdGenerator(CommitIdGenerator.valueOf(javersProperties.getCommitIdGenerator().toUpperCase()))
            .withMappingStyle(MappingStyle.valueOf(javersProperties.getMappingStyle().toUpperCase()))
            .withNewObjectsSnapshot(javersProperties.isNewObjectSnapshot())
            .withPrettyPrint(javersProperties.isPrettyPrint())
            .withTypeSafeValues(javersProperties.isTypeSafeValues())
            .withPackagesToScan(javersProperties.getPackagesToScan())
            .withPrettyPrintDateFormats(javersProperties.getPrettyPrintDateFormats());
        return this;
    }

    private void mapRegisteredClasses() {
        TypeMapper typeMapper = typeMapper();
        for (ClientsClassDefinition def : clientsClassDefinitions.values()) {
            typeMapper.registerClientsClass(def);
        }
    }

    private TypeMapper typeMapper() {
        return getContainerComponent(TypeMapper.class);
    }

    private JaversCoreConfiguration coreConfiguration() {
        return getContainerComponent(JaversCoreConfiguration.class);
    }

    private JsonConverterBuilder jsonConverterBuilder(){
        return getContainerComponent(JsonConverterBuilder.class);
    }

    private Set<JaversType> bootAddOns() {
        Set<JaversType> additionalTypes = new HashSet<>();

        for (ConditionalTypesPlugin plugin : conditionalTypesPlugins) {
            logger.info("loading "+plugin.getClass().getSimpleName()+" ...");

            plugin.beforeAssemble(this);

            additionalTypes.addAll(plugin.getNewTypes());

            AddOnsModule addOnsModule = new AddOnsModule(getContainer(), (Collection)plugin.getPropertyChangeAppenders());
            addModule(addOnsModule);
        }

        return additionalTypes;
    }

    private void bootManagedTypeModule() {
        addModule(new TypeMapperModule(getContainer()));
        mapRegisteredClasses();
    }

    /**
     * boots JsonConverter and registers domain aware typeAdapters
     */
    private Collection<JaversType> bootJsonConverter() {
        JsonConverterBuilder jsonConverterBuilder = jsonConverterBuilder();

        addModule(new ChangeTypeAdaptersModule(getContainer()));
        addModule(new CommitTypeAdaptersModule(getContainer()));

        if (new RequiredMongoSupportPredicate().test(repository)) {
            jsonConverterBuilder.registerNativeGsonDeserializer(Long.class, new MongoLong64JsonDeserializer());
        }

        jsonConverterBuilder.registerJsonTypeAdapters(getComponents(JsonTypeAdapter.class));
        jsonConverterBuilder.registerNativeGsonDeserializer(Diff.class, new DiffTypeDeserializer());
        JsonConverter jsonConverter = jsonConverterBuilder.build();
        addComponent(jsonConverter);

        return Lists.transform(jsonConverterBuilder.getValueTypes(), c -> new ValueType(c));
    }

    private void bootDateTimeProvider() {
        if (dateProvider == null) {
            dateProvider = new DefaultDateProvider();
        }
        addComponent(dateProvider);
    }

    private void bootRepository(){
        if (repository == null){
            logger.info("using fake InMemoryRepository, register actual Repository implementation via JaversBuilder.registerJaversRepository()");
            repository = new InMemoryRepository();
        }

        repository.setJsonConverter( getContainerComponent(JsonConverter.class));

        if (repository instanceof ConfigurationAware){
            ((ConfigurationAware) repository).setConfiguration(coreConfiguration());
        }

        bindComponent(JaversRepository.class, repository);

        //JaversExtendedRepository can be created after users calls JaversBuilder.registerJaversRepository()
        addComponent(JaversExtendedRepository.class);
    }

    private <T extends ClientsClassDefinition> T getClassDefinition(Class<?> baseJavaClass) {
        return (T)clientsClassDefinitions.get(baseJavaClass);
    }
}
