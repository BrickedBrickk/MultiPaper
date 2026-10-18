package puregero.multipaper;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({MultiPaperInventoryHandlerTest.class, ExternalPlayerPacketFilterTest.class, TimeUpdatePacketTest.class, MultiPaperPeerDataFallbackTest.class, EntitySwingAnimationSyncTest.class})
public class MultiPaperInventoryHandlerTestSuite {
}
