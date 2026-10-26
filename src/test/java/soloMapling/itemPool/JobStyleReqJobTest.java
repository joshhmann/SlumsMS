package soloMapling.itemPool;

import client.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobStyleReqJobTest {

    @Test
    void piratesOfBothStylesWearPirateGear() {
        // 1.6.0: getJobStyle() gives BRAWLER or GUNSLINGER for every pirate; reqJob 0 left pirate bots without a weapon
        assertEquals(16, ItemInformationProviderUtilities.getReqJobViaJobStyle(Job.BRAWLER));
        assertEquals(16, ItemInformationProviderUtilities.getReqJobViaJobStyle(Job.GUNSLINGER));
        assertEquals(16, ItemInformationProviderUtilities.getReqJobViaJobStyle(Job.PIRATE));
    }

    @Test
    void theOtherStylesKeepTheirReqJob() {
        assertEquals(1, ItemInformationProviderUtilities.getReqJobViaJobStyle(Job.WARRIOR));
        assertEquals(2, ItemInformationProviderUtilities.getReqJobViaJobStyle(Job.MAGICIAN));
        assertEquals(4, ItemInformationProviderUtilities.getReqJobViaJobStyle(Job.CROSSBOWMAN));
        assertEquals(8, ItemInformationProviderUtilities.getReqJobViaJobStyle(Job.THIEF));
        assertEquals(0, ItemInformationProviderUtilities.getReqJobViaJobStyle(Job.BEGINNER));
    }
}
