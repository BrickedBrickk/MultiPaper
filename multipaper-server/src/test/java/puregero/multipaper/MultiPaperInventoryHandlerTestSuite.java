package puregero.multipaper;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({MultiPaperInventoryHandlerTest.class, MultiPaperContainerHandlerTest.class, ExternalPlayerPacketFilterTest.class, TimeUpdatePacketTest.class, MultiPaperPeerDataFallbackTest.class, EntitySwingAnimationSyncTest.class, ServerStartMessageTrackerTest.class, PeerServerStartTrackerTest.class, MultiPaperConnectionAvailabilityTest.class, MultiPaperConnectionReplyTest.class})
public class MultiPaperInventoryHandlerTestSuite {
}
