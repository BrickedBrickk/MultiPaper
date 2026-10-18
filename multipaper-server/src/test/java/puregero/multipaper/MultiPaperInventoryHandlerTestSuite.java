package puregero.multipaper;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({MultiPaperInventoryHandlerTest.class, ExternalPlayerPacketFilterTest.class, TimeUpdatePacketTest.class})
public class MultiPaperInventoryHandlerTestSuite {
}
