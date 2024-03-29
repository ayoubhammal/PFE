package pfe.fog.cmpBF;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.apache.commons.math3.util.Pair;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.application.AppModule;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.utils.FogEvents;
import org.fog.utils.Logger;
import org.fog.utils.NetworkUsageMonitor;


public class GWFogDevice extends FogDevice {
	protected List<GWFogDevice> gwDevices;
	protected List<Integer> parentsIds = new ArrayList<Integer>();
	protected List<Boolean> isNorthLinkBusyByid = new ArrayList<Boolean>();
	protected List<Queue<Tuple>> northTupleQueues = new ArrayList<Queue<Tuple>>();
	protected List<Integer> clusterFogDevicesIds = new ArrayList<Integer>();

	private int i = 0;
	
	public GWFogDevice(String name, FogDeviceCharacteristics characteristics, VmAllocationPolicy vmAllocationPolicy,
			List<Storage> storageList, double schedulingInterval, double uplinkBandwidth, double downlinkBandwidth,
			double uplinkLatency, double ratePerMips, List<Integer> clusterFogDevicesIds) throws Exception {
		super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval, uplinkBandwidth, downlinkBandwidth, uplinkLatency, ratePerMips);
		
		this.clusterFogDevicesIds = clusterFogDevicesIds;

	}
	
	public void addParent(int patendId) {
		parentsIds.add(parentId);
		isNorthLinkBusyByid.add(false);
		northTupleQueues.add(new LinkedList<Tuple>()); // Queue est une interface !!
	}
	
	public List<Integer> getParentsId() {
		return parentsIds;
	}
	
	protected void sendUp(Tuple tuple, int linkId) {
		if (!isNorthLinkBusyByid.get(linkId)) {
			sendUpFreeLink(tuple, linkId);
		} else {
			northTupleQueues.get(linkId).add(tuple);
		}
	}
	
	protected void sendUpFreeLink(Tuple tuple, int linkId) {
		double networkDelay = tuple.getCloudletFileSize() / getUplinkBandwidth();
		
		isNorthLinkBusyByid.set(linkId, true);
		send(getId(), networkDelay, FogEvents.UPDATE_NORTH_TUPLE_QUEUE);
		send(parentsIds.get(linkId), networkDelay + getUplinkLatency(), FogEvents.TUPLE_ARRIVAL, tuple);
		NetworkUsageMonitor.sendingTuple(getUplinkLatency(), tuple.getCloudletFileSize());
	}
	
	protected void updateNorthTupleQueue(){
		int i = 0;
		for (Queue<Tuple> q : getNorthTupleQueues()) {
			if(!q.isEmpty()) {
				Tuple tuple = q.poll();
				sendUpFreeLink(tuple, i);
			} else{
				isNorthLinkBusyByid.set(i, false);
			}
			i++;
		}
	}
	
	protected void sendTupleToActuator(Tuple tuple) {
		for(Pair<Integer, Double> actuatorAssociation : getAssociatedActuatorIds()){
			int actuatorId = actuatorAssociation.getFirst();
			double delay = actuatorAssociation.getSecond();
			String actuatorType = ((Actuator)CloudSim.getEntity(actuatorId)).getActuatorType();
			if(tuple.getDestModuleName().equals(actuatorType)){
				send(actuatorId, delay, FogEvents.TUPLE_ARRIVAL, tuple);
				return;
			}
		}
	}
	
	protected void processTupleArrival(SimEvent ev){
		Tuple tuple = (Tuple)ev.getData();
		
		if(getName().equals("cloud")){
			updateCloudTraffic();
		}
		
		Logger.debug(getName(),
				"Received tuple " + tuple.getCloudletId() + " with tupleType = " + tuple.getTupleType() + "\t| Source : "
						+ CloudSim.getEntityName(ev.getSource()) + "|Dest : "
						+ CloudSim.getEntityName(ev.getDestination()));
		
		send(ev.getSource(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ACK);
		
		if (tuple.getDirection() == Tuple.ACTUATOR) {
			sendTupleToActuator(tuple);
			return;
		}

		// chercher le premier noeud capable d'executer le tuple 
		sendTupleToDeviceBestFit(tuple);
	}
	
	private void sendTupleToDeviceBestFit(Tuple tuple) {
		MatchedTuple mt = new MatchedTuple(tuple);
		
		int id = selectBestDeviceForTuple(mt, clusterFogDevicesIds);
		
		if (id != -1)
			mt.setDestinationFogDeviceId(id);
		else
			mt.setDestinationFogDeviceId(CloudSim.getEntity("cloud").getId());	
		
		int link = -1;
		if (parentsIds.contains(mt.getDestinationFogDevice()))
			link = parentsIds.indexOf(mt.getDestinationFogDevice());
		
		sendUp(mt, link == -1 ? i : link);
		i = (i + 1) % parentsIds.size();
	}
	
	private int selectBestDeviceForTuple(MatchedTuple mt, List<Integer> prepositionsList) {
		// Le meilleur c'est celui qui maximise l'utilisation
		double maxDist = calculateDistance((ClusterFogDevice)CloudSim.getEntity(prepositionsList.get(0)), mt);
		int bestId = prepositionsList.get(0);
		for (int id : prepositionsList) {
			double dist = calculateDistance((ClusterFogDevice)CloudSim.getEntity(id), mt);
			if (maxDist > 1) {
				maxDist = dist;
				bestId = id;
			} else if (dist <= 1 && maxDist <= dist) {
				maxDist = dist;
				bestId = id;
			}
		}
		
		double dist = calculateDistance((ClusterFogDevice)CloudSim.getEntity(bestId), mt);
		if (dist > 1) {
			return -1;
		}
		return bestId;
	}
	
	private double calculateDistance(ClusterFogDevice d, Tuple t) {
		AppModule m = ((Controller)CloudSim.getEntity(getControllerId())).getApplications().get(t.getAppId()).getModuleByName(t.getDestModuleName());
		double hostAvailableMips = d.getHost().getAvailableMips();
		double hostMips = d.getHost().getTotalMips();
		double hostUsedMips = hostMips - hostAvailableMips;
		double moduleMips = m.getMips();
		((MatchedTuple)t).destModuleMips = moduleMips;
//		System.out.println("-- Host: " + d.getName() + " MipsAvailable: " + hostAvailableMips + " Tuple: " + t.getCloudletId() + " Mips: " + moduleMips + " Dist: " + (hostAvailableMips > moduleMips ? hostAvailableMips - moduleMips : -1));
//		return hostAvailableMips >= moduleMips ? hostUsedMips : -1;
		
		// formule de l'article
//		System.out.println("-- Host: " + d.getName() + " MipsAvailable: " + hostAvailableMips + " total : " + hostMips + " used : " + hostUsedMips + " Tuple: " + t.getCloudletId() + " Mips: " + moduleMips + " Dist: " + ((hostUsedMips + moduleMips) / hostMips));
		return (hostUsedMips + moduleMips) / hostMips;
	}
	
	
	public List<Integer> getParentsIds() {
		return parentsIds;
	}

	public void setParentsIds(List<Integer> parentsIds) {
		this.parentsIds = parentsIds;
	}

	public List<Queue<Tuple>> getNorthTupleQueues() {
		return northTupleQueues;
	}

	public void setNorthTupleQueues(List<Queue<Tuple>> northTupleQueues) {
		this.northTupleQueues = northTupleQueues;
	}

	
	public List<Integer> getClusterFogDevicesIds() {
		return clusterFogDevicesIds;
	}

	public void setClusterFogDevicesIds(List<Integer> clusterFogDevicesIds) {
		this.clusterFogDevicesIds = clusterFogDevicesIds;
	}
	
	public List<GWFogDevice> getGwDevices() {
		return gwDevices;
	}

	public void setGwDevices(List<GWFogDevice> gwDevices) {
		this.gwDevices = gwDevices;
	}
}

