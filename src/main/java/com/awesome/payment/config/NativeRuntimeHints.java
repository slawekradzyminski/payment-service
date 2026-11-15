package com.awesome.payment.config;

import com.awesome.payment.client.EventFeedPage;
import com.awesome.payment.client.FeedEvent;
import com.awesome.payment.client.InternalOrderDto;
import com.awesome.payment.client.JwkSet;
import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.client.PaymentResultRequest;
import com.awesome.payment.client.PaymentResultResponse;
import com.awesome.payment.dto.ErrorDto;
import com.awesome.payment.dto.PageDto;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.util.List;
import java.util.regex.Pattern;
import java.util.UUID;

/**
 * GraalVM native-image hints. Spring AOT evaluates these at build time; the
 * running image does no classpath scanning.
 *
 * <ul>
 *   <li>Internal-API records (including the event feed page and its events, whose {@code data}
 *       stays a {@code JsonNode}) are (de)serialised by Jackson through {@code RestClient}
 *       (and the result payload by the outbox's own {@code JsonMapper}), which AOT cannot
 *       infer from controller signatures.</li>
 *   <li>JJWT's API module instantiates its implementation classes by name
 *       ({@code Classes.newInstance("io.jsonwebtoken.impl...")}); exactly those
 *       classes (and the Jackson (de)serializers found through ServiceLoader)
 *       are registered for reflective construction.</li>
 *   <li>Hibernate's multi-id loader instantiates {@code UUID[]} (the payment and refund
 *       id type) reflectively.</li>
 *   <li>Flyway migrations and the Postgres driver are covered by Spring Boot's
 *       hints and the GraalVM reachability-metadata repository
 *       ({@code add-reachability-metadata} in the {@code native} profile).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeRuntimeHints.Registrar.class)
@RegisterReflectionForBinding({
    InternalOrderDto.class,
    OrderStatus.class,
    PaymentResultRequest.class,
    PaymentResultResponse.class,
    JwkSet.class,
    JwkSet.Jwk.class,
    EventFeedPage.class,
    FeedEvent.class,
    PageDto.class,
    ErrorDto.class
})
public class NativeRuntimeHints {

    static class Registrar implements RuntimeHintsRegistrar {

        /** Implementation classes that jjwt-api loads by name (see {@code io.jsonwebtoken.lang.Classes}). */
        static final List<String> JJWT_REFLECTIVE_TYPES = List.of(
                "io.jsonwebtoken.impl.DefaultClaimsBuilder$Supplier",
                "io.jsonwebtoken.impl.DefaultJwtBuilder$Supplier",
                "io.jsonwebtoken.impl.DefaultJwtHeaderBuilder$Supplier",
                "io.jsonwebtoken.impl.DefaultJwtParserBuilder$Supplier",
                "io.jsonwebtoken.impl.io.StandardCompressionAlgorithms",
                "io.jsonwebtoken.impl.security.DefaultDynamicJwkBuilder$Supplier",
                "io.jsonwebtoken.impl.security.DefaultJwkParserBuilder$Supplier",
                "io.jsonwebtoken.impl.security.DefaultJwkSetBuilder$Supplier",
                "io.jsonwebtoken.impl.security.DefaultJwkSetParserBuilder$Supplier",
                "io.jsonwebtoken.impl.security.DefaultKeyOperationBuilder$Supplier",
                "io.jsonwebtoken.impl.security.DefaultKeyOperationPolicyBuilder$Supplier",
                "io.jsonwebtoken.impl.security.JwksBridge",
                "io.jsonwebtoken.impl.security.KeysBridge",
                "io.jsonwebtoken.impl.security.StandardCurves",
                "io.jsonwebtoken.impl.security.StandardEncryptionAlgorithms",
                "io.jsonwebtoken.impl.security.StandardHashAlgorithms",
                "io.jsonwebtoken.impl.security.StandardKeyAlgorithms",
                "io.jsonwebtoken.impl.security.StandardKeyOperations",
                "io.jsonwebtoken.impl.security.StandardSecureDigestAlgorithms",
                "io.jsonwebtoken.jackson.io.JacksonDeserializer",
                "io.jsonwebtoken.jackson.io.JacksonSerializer");

        /** Artemis JMS client types Spring Boot configures through bean-property reflection (as in the backend). */
        static final List<String> JMS_REFLECTIVE_TYPES = List.of(
                "org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory",
                "org.apache.activemq.artemis.jms.client.ActiveMQJMSConnectionFactory");

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            registerArtemisClient(hints);
            for (String type : JJWT_REFLECTIVE_TYPES) {
                hints.reflection().registerType(TypeReference.of(type), MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            }
            // Hibernate's multi-id loader allocates an array of the id type reflectively (UUID ids).
            hints.reflection().registerType(UUID[].class);
            hints.resources().registerPattern("db/migration/*.sql");
        }

        /**
         * The Artemis JMS client of the broker event transport (as in the backend and notification-service):
         * the connection factory instantiates {@code Calendar[]} reflectively, Spring Boot configures it
         * through bean-property reflection, and Artemis logger and message-bundle interfaces load their
         * generated {@code *_impl} classes by name. The rest is in
         * {@code META-INF/native-image/com.awesome/*-jms/reachability-metadata.json}.
         */
        private static void registerArtemisClient(RuntimeHints hints) {
            hints.reflection().registerType(TypeReference.of("java.util.Calendar[]"),
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            for (String type : JMS_REFLECTIVE_TYPES) {
                hints.reflection().registerType(TypeReference.of(type),
                        MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            }
            ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
                @Override
                protected boolean isCandidateComponent(
                        org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                    return true;
                }
            };
            scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*_impl")));
            for (BeanDefinition candidate : scanner.findCandidateComponents("org.apache.activemq.artemis")) {
                String name = candidate.getBeanClassName();
                if (name != null && name.endsWith("_impl")) {
                    hints.reflection().registerType(TypeReference.of(name),
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS);
                }
            }
        }
    }
}
