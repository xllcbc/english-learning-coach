package com.writelab.speech;

import com.writelab.agent.TutorModelClient;
import com.writelab.feedback.FeedbackModel;
import com.writelab.tools.ToolRegistry;
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
    SpeakingPracticeService speakingPracticeService(
            SpeechModel speechModel,
            SpeakingSessionRepository speakingSessionRepository,
            TutorModelClient tutorModelClient,
            ToolRegistry toolRegistry,
            FeedbackModel feedbackModel
    ) {
        return new SpeakingPracticeService(
                speechModel,
                speakingSessionRepository,
                tutorModelClient,
                toolRegistry,
                feedbackModel);
    }

    @Bean
    ServletContextInitializer speechEndpointRegistrar(
            SpeakingPracticeService speakingPracticeService,
            ObjectMapper objectMapper
    ) {
        return servletContext -> servletContext.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                registerEndpoint(event.getServletContext(), speakingPracticeService, objectMapper);
            }
        });
    }

    private static void registerEndpoint(
            ServletContext servletContext,
            SpeakingPracticeService speakingPracticeService,
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
                    speakingPracticeService, objectMapper, PATH));
        } catch (DeploymentException deploymentFailure) {
            throw new IllegalStateException("Cannot register the voice endpoint at " + PATH,
                    deploymentFailure);
        }
    }
}
