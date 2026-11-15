package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.ChatBridgeTest.chatPrompt;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.errorResponse;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.matchedBot;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.chat.ChatRoute;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingSession;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A prompt that takes the player's next chat line needs the chat permission and does not wait forever. */
class ChatPromptTest {
    @TempDir
    Path dir;

    @Test
    void takingAChatLineNeedsTheChatPermission() throws Exception {
        try (Harness h = new Harness(dir)) {
            PairingSession session = h.core.pairing().begin("bots.example");
            try (FakePlugin p = RemoteTest.tls(h, "remote-1")) {
                RemoteTest.pair(h, p, session.code());
                int s = matchedBot(h, p, "Miner");
                // Remote devices have picks and prompts, but not chat.
                long req = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, chatPrompt(PromptKind.TEXT, null));
                errorResponse(p, req, "permission_denied", CoreMessages.PROMPT_REQUEST);
                assertNull(h.core.input().active());
                assertEquals(new ChatRoute.PassThrough("my password"), h.core.chat().route("my password"),
                        "the line goes to the server as usual");

                h.core.devices().setPermission("remote-1", Permission.CHAT, true);
                long allowed = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, chatPrompt(PromptKind.TEXT, null));
                p.sync();
                assertNotNull(h.core.input().active());
                assertInstanceOf(ChatRoute.CapturedPrompt.class, h.core.chat().route("a house"));
                assertEquals(new PromptResult("a house", false, "chat"), response(p, CoreMessages.PROMPT_RESULT, allowed));
            }
        }
    }

    @Test
    void anUnansweredChatPromptLetsGoOfTheChat() throws Exception {
        try (Harness h = new Harness(dir, b -> b.chatPromptWindowMs(10_000), DeviceApprover.ALLOW_ONCE);
                FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            long req = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, chatPrompt(PromptKind.TEXT, null));
            p.sync();
            LockoutTest.advance(h, p, 9_000);
            assertNotNull(h.core.input().active(), "still waiting inside the window");
            LockoutTest.advance(h, p, 1_000);
            h.tick();

            // Its own timeout was 30 s; the chat is given back after 10.
            PromptResult result = response(p, CoreMessages.PROMPT_RESULT, req);
            assertTrue(result.timedOut());
            assertNull(h.core.input().active());
            assertEquals(new ChatRoute.PassThrough("hello"), h.core.chat().route("hello"));

            // Asking again right away is refused, as after a dismissal.
            long again = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, chatPrompt(PromptKind.TEXT, null));
            errorResponse(p, again, "cooldown", CoreMessages.PROMPT_REQUEST);
        }
    }
}
