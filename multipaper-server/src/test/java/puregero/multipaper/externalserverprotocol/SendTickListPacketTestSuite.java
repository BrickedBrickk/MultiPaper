package puregero.multipaper.externalserverprotocol;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({SendTickListPacketTest.class, TickListAckPacketTest.class})
public class SendTickListPacketTestSuite {
}
