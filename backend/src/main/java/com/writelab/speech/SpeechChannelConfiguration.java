package com.writelab.speech;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers the speech endpoint at the WebSocket container level. The endpoint is deliberately not
 * a Spring MVC controller: the speech channel is transport-adapted from the turn service, and text
 * fallbacks share the same connection (ADR-0009, ADR-0011).
 *
 * <p>Registration happens in a {@link ServletContextListener} rather than directly in the
 * initializer because Tomcat's WebSocket {@code ServletContainerInitializer} may run after the
 * Spring initializers; by the listener phase the {@link ServerContainer} attribute is present.
 */
@Configuration
public class SpeechChannelConfiguration {

    public static final String PATH = "/ws/speaking";

    @Bean
    SpeechTurnService speechTurnService(SpeechModel speechModel) {
        return new SpeechTurnService(speechModel);
    }

    @Bean
    ServletContextInitializer speechEndpointRegistrar(
            SpeechTurnService speechTurnService,
            ObjectMapper objectMapper
    ) {
        return servletContext -> servletContext.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                registerEndpoint(event.getServletContext(), speechTurnService, objectMapper);
            }
        });
    }

    private static void registerEndpoint(
            ServletContext servletContext,
            SpeechTurnService speechTurnService,
            ObjectMapper objectMapper
    ) {
        ServerContainer container = (ServerContainer) servletContext.getAttribute(
                ServerContainer.class.getName());
        if (container == null) {
            throw new IllegalStateException(
                    "The WebSocket container is not available; cannot register " + PATH);
        }
        try {
            container.addEndpoint(SpeechEndpoint.serverEndpointConfig(
                    speechTurnService, objectMapper, PATH));
        } catch (DeploymentException deploymentFailure) {
            throw new IllegalStateException("Cannot register the voice endpoint at " + PATH,
                    deploymentFailure);
        }
    }
}
