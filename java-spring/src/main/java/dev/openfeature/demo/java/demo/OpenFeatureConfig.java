package dev.openfeature.demo.java.demo;

import dev.openfeature.contrib.providers.flagd.Config;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import dev.openfeature.contrib.providers.flagd.FlagdProvider;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.SpringVersion;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.HashMap;

@Configuration
public class OpenFeatureConfig implements WebMvcConfigurer {

    @PostConstruct
    public void initProvider() {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        FlagdOptions flagdOptions = FlagdOptions.builder()
                .resolverType(Config.Resolver.FILE)
                .offlineFlagSourcePath("./flags.json")
                .build();

        api.setProviderAndWait(new FlagdProvider(flagdOptions));

        HashMap<String, Value> attributes = new HashMap<>();
        attributes.put("springVersion", new Value(SpringVersion.getVersion()));
        ImmutableContext evaluationContext = new ImmutableContext(attributes);
        api.setEvaluationContext(evaluationContext);

        api.addHooks(new CustomHook());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LanguageInterceptor());
    }
}
