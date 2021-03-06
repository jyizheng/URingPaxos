package ch.usi.da.paxos;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import ch.usi.da.paxos.api.PaxosRole;
import ch.usi.da.paxos.message.Control;
import ch.usi.da.paxos.message.ControlType;
import ch.usi.da.paxos.message.Message;
import ch.usi.da.paxos.message.MessageType;
import ch.usi.da.paxos.message.Value;

public class TestMessage {

	@Test
	public void serializeMessage() throws Exception {
		Message m = new Message(1L, 0, PaxosRole.Leader, MessageType.Value, 0, null);
		assertEquals(m,Message.fromWire(Message.toWire(m)));
		assertEquals(Message.getCRC32(m),Message.getCRC32(Message.fromWire(Message.toWire(m))));
		
		m = new Message(1L, 0, PaxosRole.Leader, MessageType.Value, 0, null);
		m.incrementVoteCount();
		assertEquals(m,Message.fromWire(Message.toWire(m)));
		assertEquals(Message.getCRC32(m),Message.getCRC32(Message.fromWire(Message.toWire(m))));
		assertEquals(1,Message.fromWire(Message.toWire(m)).getVoteCount());
		
		m = new Message(5L, 999, PaxosRole.Acceptor, MessageType.Phase1, 0, 0, null);
		assertEquals(m,Message.fromWire(Message.toWire(m)));
		assertEquals(Message.getCRC32(m),Message.getCRC32(Message.fromWire(Message.toWire(m))));

		Value v = new Value("Test", "Value".getBytes());
		m = new Message(1L, 10, PaxosRole.Proposer, MessageType.Safe, 999, 10, v);
		assertEquals(m,Message.fromWire(Message.toWire(m)));
		assertEquals(Message.getCRC32(m),Message.getCRC32(Message.fromWire(Message.toWire(m))));

		v = new Value(Value.getSkipID(), "Value".getBytes());
		m = new Message(1L, 10, PaxosRole.Proposer, MessageType.Safe, 999, 20, v);
		assertEquals(m,Message.fromWire(Message.toWire(m)));
		assertEquals(Message.getCRC32(m),Message.getCRC32(Message.fromWire(Message.toWire(m))));
		
	}

	@Test
	public void serializeControl() throws Exception {
		Control c = new Control(1, ControlType.Subscribe, 2, 5);
		System.err.println(c);
		Value v = new Value(Value.getControlID(),Control.toWire(c));
		System.err.println(v);
		System.err.println(v.asString());		
		assertEquals(c,Control.fromWire(Control.toWire(c)));
		assertEquals(true,c.equals(c));
		Control c2 = new Control(1, ControlType.Subscribe, 2, 6);
		assertEquals(false,c.equals(c2));
		Control c3 = new Control(2, ControlType.Subscribe, 2, 5);
		assertEquals(false,c.equals(c3));
		Control c4 = new Control(1, ControlType.Prepare, 2, 5);
		assertEquals(false,c.equals(c4));
	}

}
