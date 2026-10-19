package net.server.handlers.login;
import client.Client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RegistrationWebhookTest {
    @Test void missingEndpointSkipsNotificationWithoutTouchingAccountState() {
        Client client = mock(Client.class);
        for (String endpoint : new String[]{null, "", "   "})
            assertNull(LoginPasswordHandler.buildDiscordRegistrationWebhookMessage(client, "example", "example.invalid", endpoint));
        verifyNoInteractions(client);
    }
    @Test void configuredEndpointBuildsNotificationWithoutSendingIt() {
        Client client = mock(Client.class);
        when(client.getAccID()).thenReturn(42);
        assertNotNull(LoginPasswordHandler.buildDiscordRegistrationWebhookMessage(client, "example", "example.invalid", "https://example.invalid/registration"));
        verify(client).getAccID();
    }
}
