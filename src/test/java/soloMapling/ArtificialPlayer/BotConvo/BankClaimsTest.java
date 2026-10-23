package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc6 say-do: the line bank knows which lines claim an action (tagged at pack time) and a reply only picks a claim
 * line its decided action backs ("brb pots" never; "omw" only for a walk that happens).
 */
class BankClaimsTest {

    private static ChatBank bank(String... rows) throws Exception {
        return ChatBank.read(new ByteArrayInputStream(String.join("\n", rows).getBytes(StandardCharsets.UTF_8)));
    }

    private static ChatBank.Query q(String intent, long claimsOk) {
        return new ChatBank.Query(intent, null, "neutral", null, 0, Map.of(), claimsOk);
    }

    @Test
    void thePackTagsClaimsIntoTheTagsField() {
        assertEquals("7\tbrush_rwt\tany\tany\tany\tclaim:resupply\tbrb pots got 3 stacks",
                ChatBankGen.withClaimTags("7\tbrush_rwt\tany\tany\tany\t-\tbrb pots got 3 stacks"));
        assertEquals("8\ttalk\tany\tany\tany\ttown\tlol ya", ChatBankGen.withClaimTags("8\ttalk\tany\tany\tany\ttown\tlol ya"));
        assertEquals("9\tcome_yes\tany\tany\tany\tgrind,claim:come\tomw", ChatBankGen.withClaimTags(
                "9\tcome_yes\tany\tany\tany\tgrind,claim:come\tomw"), "re-packing does not tag twice");
    }

    @Test
    void aClaimLineIsNeverPickedWhenTheReplyDoesNotBackIt() throws Exception {
        ChatBank b = bank(ChatBank.HEADER_V2,
                "1\tbrush\tany\tneutral\tany\tclaim:resupply\tbrb pots",
                "2\tbrush\tany\tneutral\tany\t-\tnah im good");
        assertEquals(ChatBank.claimMask(EnumSet.of(ClaimRules.Kind.RESUPPLY)), b.claimsOf(1));
        long none = ChatBank.claimMask(ConvoLines.claimsFor("brush"));
        Random r0 = new Random(7);
        for (int i = 0; i < 50; i++) {
            assertEquals(2, b.pick(q("brush", none), null, null, r0).id());
        }
        boolean sawClaim = false;
        Random rng = new Random(42); // one generator: small seeds all open on about the same first double
        for (int i = 0; i < 50 && !sawClaim; i++) {
            sawClaim = b.pick(q("brush", -1L), null, null, rng).id() == 1;
        }
        assertTrue(sawClaim, "callers that allow every claim (tools, old tests) still see the line");
    }

    @Test
    void omwOnlyForAWalkThatHappens() throws Exception {
        ChatBank b = bank(ChatBank.HEADER_V2,
                "1\tcome_yes\tany\tneutral\tany\tclaim:come\tomw",
                "2\tcome_no\tany\tneutral\tany\tclaim:come\tomw jk",
                "3\tcome_no\tany\tneutral\tany\t-\tcant rn");
        assertEquals(1, b.pick(q("come_yes", ChatBank.claimMask(ConvoLines.claimsFor("come_yes"))), null, null,
                new Random(1)).id());
        Random r1 = new Random(11);
        for (int i = 0; i < 30; i++) {
            assertNotEquals(2, b.pick(q("come_no", ChatBank.claimMask(ConvoLines.claimsFor("come_no"))), null, null,
                    r1).id());
        }
    }

    @Test
    void anOlderPackIsTaggedAtLoad() throws Exception {
        ChatBank b = bank("#chatbank v1", "5\ttalk\tany\tneutral\tany\t-\tgtg cya");
        assertEquals(ChatBank.claimMask(EnumSet.of(ClaimRules.Kind.LEAVING)), b.claimsOf(5));
    }

    @Test
    void theShippedBankIsTagged() throws Exception {
        java.nio.file.Path f = java.nio.file.Path.of(ChatBank.RESOURCE);
        if (!java.nio.file.Files.isRegularFile(f)) {
            return;
        }
        try (var in = new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(f))) {
            String head = new String(in.readNBytes(64), StandardCharsets.UTF_8);
            assertTrue(head.startsWith(ChatBank.HEADER_V2), "chatbank.bin was packed with the claim tags");
        }
    }
}
