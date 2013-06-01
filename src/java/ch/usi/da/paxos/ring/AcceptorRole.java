package ch.usi.da.paxos.ring;
/* 
 * Copyright (c) 2013 Università della Svizzera italiana (USI)
 * 
 * This file is part of URingPaxos.
 *
 * URingPaxos is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * URingPaxos is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with URingPaxos.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

import ch.usi.da.paxos.message.Message;
import ch.usi.da.paxos.message.MessageType;
import ch.usi.da.paxos.message.PaxosRole;
import ch.usi.da.paxos.message.Value;
import ch.usi.da.paxos.storage.Decision;
import ch.usi.da.paxos.storage.NoStorage;
import ch.usi.da.paxos.storage.StableStorage;

/**
 * Name: AcceptorRole<br>
 * Description: <br>
 * 
 * Creation date: Aug 12, 2012<br>
 * $Id$
 * 
 * @author Samuel Benz <benz@geoid.ch>
 */
public class AcceptorRole extends Role {
	
	private final static Logger logger = Logger.getLogger(AcceptorRole.class);

	private final RingManager ring;
	
	private StableStorage storage;
	
	private final Map<Integer,Integer> promised = new ConcurrentHashMap<Integer,Integer>();

	private final Map<String,Value> learned = new ConcurrentHashMap<String,Value>();

	private int highest_seen_instance = 0;
	
	private int highest_accepted_instance = 0;
	
	/**
	 * @param ring
	 */
	public AcceptorRole(RingManager ring) {
		this.ring = ring;
		String storage_class = "ch.usi.da.paxos.storage.NoStorage";
		if(ring.getConfiguration().containsKey(ConfigKey.stable_storage)){
			storage_class = ring.getConfiguration().get(ConfigKey.stable_storage);
		}
		try {
			Class<?> store = Class.forName(storage_class);
			storage = (StableStorage) store.newInstance();
			logger.info("Acceptor stable storage engine: " + store);
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
			storage = new NoStorage();
			logger.error("Could not initilaize stable storage engine!", e);
		}
	}

	@Override
	public void run() {
		ring.getNetwork().registerCallback(this);
	}

	public void deliver(RingManager fromRing,Message m){
		/*if(logger.isDebugEnabled()){
			logger.debug("acceptor " + ring.getNodeID() + " received " + m);
		}*/
		int instance = m.getInstance();
		int ballot = 0;
		Value value = null;

		if(m.getValue() != null && learned.containsKey(m.getValue().getID())){
			value = learned.get(m.getValue().getID());
		}

		// read stable storage/ promised ballots
		if(instance <= highest_accepted_instance && storage.contains(instance)){ 
			Decision d = storage.get(instance);
			if(d != null){
				ballot = d.getBallot();
				value = d.getValue();
			}
		}else if(promised.containsKey(instance)){
			ballot = promised.get(instance);
		}
		
		// process messages
		if(m.getType() == MessageType.Phase1){
			if(m.getBallot() > ballot){ // 1b
				ballot = m.getBallot();
				m.incrementVoteCount();
				promised.put(instance,ballot);
				if(ring.getNodeID() == ring.getLastAcceptor()){
					Message n = new Message(instance,m.getSender(),PaxosRole.Leader,MessageType.Phase1,ballot,value);
					n.setVoteCount(m.getVoteCount());
					ring.getNetwork().send(n);
				}else{
					Message n = new Message(instance,m.getSender(),PaxosRole.Acceptor,MessageType.Phase1,ballot,value);
					n.setVoteCount(m.getVoteCount());
					ring.getNetwork().send(n); // send directly to the network					
				}
			}
		}else if(m.getType() == MessageType.Phase1Range){
			value = m.getValue();
			if(instance > highest_seen_instance){ // reject if start instance is leq highest promised or decided instance
				ballot = m.getBallot();
				m.incrementVoteCount();
				int p1_range = NetworkManager.byteToInt(value.getValue());
				for(int i=m.getInstance();i<p1_range+m.getInstance();i++){
					promised.put(i,ballot);
					if(i>highest_seen_instance){
						highest_seen_instance=i;
					}
				}
				if(ring.getNodeID() == ring.getLastAcceptor()){
					Message n = new Message(instance,m.getSender(),PaxosRole.Leader,MessageType.Phase1Range,ballot,value);
					n.setVoteCount(m.getVoteCount());
					ring.getNetwork().send(n);
				}else{
					Message n = new Message(instance,m.getSender(),PaxosRole.Acceptor,MessageType.Phase1Range,ballot,value);
					n.setVoteCount(m.getVoteCount());
					ring.getNetwork().send(n); // send directly to the network					
				}
			}			
		}else if(m.getType() == MessageType.Phase2){
			if(m.getBallot() >= ballot){ // >= see P1a
				ballot = m.getBallot();
				if(value == null){ // you can increase the ballot, but never change the value
					value = m.getValue();
				}
				if(value != null){ // 2b
					if(value.equals(m.getValue())){
						m.incrementVoteCount(); //TODO: this could cause undecided instances when |coord| > 1
					}
					if(ring.getNodeID() == ring.getLastAcceptor()){
						if(m.getVoteCount() >= ring.getQuorum()){
							Decision d = new Decision(instance,ballot,value);
							storage.put(instance,d);
							if(instance>highest_accepted_instance){
								highest_accepted_instance=instance;
							}
							learned.remove(value.getID());
							promised.remove(instance);
							Message n = new Message(instance,m.getSender(),PaxosRole.Learner,MessageType.Decision,ballot,value);
							if(ring.getNetwork().getLeader() != null){
								ring.getNetwork().getLeader().deliver(ring,n);
							}
							if(ring.getNetwork().getLearner() != null){
								ring.getNetwork().getLearner().deliver(ring,n);
							}
							if(ring.getNetwork().getProposer() != null){
								ring.getNetwork().getProposer().deliver(ring,n);
							}
							// D,v -> until predecessor(P0)
							if((ring.getNodeID() >= m.getSender() && m.getSender() != ring.getCoordinatorID()) || ring.getRingSuccessor(ring.getNodeID()) == ring.getCoordinatorID()){
								// remove not needed values
								Message o = new Message(n.getInstance(),n.getSender(),n.getReceiver(),n.getType(),n.getBallot(),new Value(n.getValue().getID(),new byte[0]));
								ring.getNetwork().send(o);
							}else{
								ring.getNetwork().send(n);
							}
						}else{
							logger.error("Not decided at end of ring!");
						}
					}else{
						if(!learned.containsKey(value.getID())){
							learned.put(value.getID(),value);
						}
						// D,v -> until predecessor(P0)
						Message n = new Message(m.getInstance(),m.getSender(),m.getReceiver(),m.getType(),ballot,value);
						n.setVoteCount(m.getVoteCount());
						if((ring.isNodeCoordinator() && ring.getNodeID() == m.getSender())){
							ring.getNetwork().send(n);
						}else if(ring.getNodeID() == ring.getRingPredecessor(m.getSender()) || ring.getNodeID() == m.getSender()){ //remove values from phase2 after "this"; they saw Value!
							// remove not needed values
							Message o = new Message(n.getInstance(),n.getSender(),n.getReceiver(),n.getType(),n.getBallot(),new Value(n.getValue().getID(),new byte[0]));
							o.setVoteCount(n.getVoteCount());
							ring.getNetwork().send(o);
						}else{
							ring.getNetwork().send(n);
						}
					}
				}
			}
		}else if(m.getType() == MessageType.Value){
			learned.put(m.getValue().getID(),m.getValue());
		}else if(m.getType() == MessageType.Decision){
			if(value != null && learned.containsKey(value.getID())){
				Decision d = new Decision(instance,m.getBallot(),learned.get(value.getID()));
				storage.put(instance,d);
				if(instance>highest_accepted_instance){
					highest_accepted_instance=instance;
				}
				learned.remove(value.getID());
				promised.remove(instance);
			}
		}
		
		if(instance>highest_seen_instance){
			highest_seen_instance=instance;
		}
	}

	/**
	 * @return the promised
	 */
	public Map<Integer, Integer> getPromised() {
		return promised;
	}

	/**
	 * @return the learned
	 */
	public Map<String, Value> getLearned() {
		return learned;
	}

	/**
	 * @return the stable storage
	 */
	public StableStorage getStableStorage() {
		return storage;
	}
}
