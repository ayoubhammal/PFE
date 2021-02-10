package pfe.fog.entities;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.PhysicalTopology;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

public class Test {
	static List<FogDevice> fogDevices = new ArrayList<FogDevice>();
	static List<Sensor> sensors = new ArrayList<Sensor>();
	static List<Actuator> actuators = new ArrayList<Actuator>();
	static List<Integer> clusterFogDevicesIds = new ArrayList<Integer>();
	static List<ClusterFogDevice> clusterFogDevices = new ArrayList<ClusterFogDevice>();
	
	static int nbOfLayers = 10;
	static int nbOfNodePerLayer = 3;
	static int tokenDelay = 2;
	static double transmitRate = 1;
	
	static int numberOfSensorTypes = 5;
	
	public static void main(String[] args) {
		GWFogDevice.tokenDelay = tokenDelay;
		try {
			Log.disable();
			Log.printLine("Initialisation");
			int num_user = 1;
			Calendar calendar = Calendar.getInstance();
			boolean trace_flag = true;
			
			CloudSim.init(num_user, calendar, trace_flag);
			
			String appId = "test_app";
			
			// ?????
			FogBroker broker = new FogBroker("broker");
			
			/* PhysicalTopology physicalTopology = JsonToTopology.getPhysicalTopology(broker.getId(), 
					appId, 
					topologyFile); */
			PhysicalTopology physicalTopology = createPhysicalTopology(broker.getId(), appId);
			
			Application application = createApplication(appId, broker.getId());
			application.setUserId(broker.getId());
			
			ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
			
			for (int i = 0; i < numberOfSensorTypes; i++) {
				for (ClusterFogDevice d : clusterFogDevices) {
					moduleMapping.addModuleToDevice("m" + (i + 1), d.getName());
				}
				moduleMapping.addModuleToDevice("m" + (i + 1), "cloud");
			}

			
			
			Controller controller = new Controller("master-controller", 
					physicalTopology.getFogDevices(), 
					physicalTopology.getSensors(), 
					physicalTopology.getActuators());
			
			ModulePlacementMapping mpm = new ModulePlacementMapping(physicalTopology.getFogDevices(),
					application,
					moduleMapping
			);

			controller.submitApplication(application, 0,
					mpm);


			TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());

			CloudSim.startSimulation();

			CloudSim.stopSimulation();
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private static PhysicalTopology createPhysicalTopology(int userId, String appId) {
		ClusterFogDevice cloud = createClusterFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16*103, 16*83.25);
		cloud.setParentId(-1);
		fogDevices.add(cloud);
		// creation cluster
		List<FogDevice> previousLayer = new ArrayList<FogDevice>();
		List<FogDevice> currentLayer = new ArrayList<FogDevice>();
		
		int mips = 1000, ram = 1000;
		for (int i = 0; i < nbOfLayers; i++) {
			for (int j = 0; j < nbOfNodePerLayer; j++) {
				ClusterFogDevice d = createClusterFogDevice("n" + i + "/" + j, mips, ram, 10000, 10000, i + 1, 0.0, 100, 50);
				mips = mips + 100 == 2000 ? 1000 : mips + 100;
				ram = ram + 200 == 3000 ? 1000 : ram + 200;
				currentLayer.add(d);
				fogDevices.add(d);
				clusterFogDevices.add(d);
				clusterFogDevicesIds.add(d.getId());
				if (previousLayer.size() == 0) {
					d.setParentId(cloud.getId());
					d.addParent(cloud.getId());
				} else {
					for (FogDevice cd : previousLayer) {
						d.setParentId(cd.getId());
						d.addParent(cd.getId());
					}
				}
			}
			
			previousLayer.clear();
			previousLayer = new ArrayList<FogDevice>(currentLayer);
			currentLayer.clear();
		}
		// creation gw
		GWFogDevice lastGw = null;
		for (int j = 0; j < nbOfNodePerLayer; j++) {
			GWFogDevice gwd = createGWFogDevice("GW" + j, 2200, 4000, 10000, 10000,  nbOfLayers, 0.0, 100, 50, clusterFogDevicesIds);
			currentLayer.add(gwd);
			fogDevices.add(gwd);
			
			for (int i = 0; i < numberOfSensorTypes; i++) {
				Sensor s = new Sensor("s" + j + "/" + i, "T" + (i + 1), userId, appId, new DeterministicDistribution(transmitRate));
				sensors.add(s);
				s.setGatewayDeviceId(gwd.getId());
				s.setLatency(1.0);
			}
			Actuator a = new Actuator("a" + j, userId, appId, "A1");
			actuators.add(a);
			a.setGatewayDeviceId(gwd.getId());
			a.setLatency(1.0);
			for (FogDevice cd : previousLayer) {
				gwd.setParentId(cd.getId());
				gwd.addParent(cd.getId());
			}
			lastGw = gwd;
		}
		lastGw.setToken(true);
		
		
		PhysicalTopology pt = new PhysicalTopology();
		pt.setFogDevices(fogDevices);
		pt.setSensors(sensors);
		pt.setActuators(actuators);
		
		return pt;
	}
	
	private static FogDevice createFogDevice(String nodeName, long mips,
			int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower) {
		
		List<Pe> peList = new ArrayList<Pe>();

		// 3. Create PEs and add these into a list.
		peList.add(new Pe(0, new PeProvisionerOverbooking(mips))); // need to store Pe id and MIPS Rating

		int hostId = FogUtils.generateEntityId();
		long storage = 1000000; // host storage
		int bw = 10000;

		PowerHost host = new PowerHost(
				hostId,
				new RamProvisionerSimple(ram),
				new BwProvisionerOverbooking(bw),
				storage,
				peList,
				new StreamOperatorScheduler(peList),
				new FogLinearPowerModel(busyPower, idlePower)
			);
		//new FogLinearPowerModel(busyPower, idlePower)

		List<Host> hostList = new ArrayList<Host>();
		hostList.add(host);

		String arch = "x86"; // system architecture
		String os = "Linux"; // operating system
		String vmm = "Xen";
		double time_zone = 10.0; // time zone this resource located
		double cost = 3.0; // the cost of using processing in this resource
		double costPerMem = 0.05; // the cost of using memory in this resource
		double costPerStorage = 0.001; // the cost of using storage in this
										// resource
		double costPerBw = 0.0; // the cost of using bw in this resource
		LinkedList<Storage> storageList = new LinkedList<Storage>(); // we are not adding SAN
													// devices by now

		FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
				arch, os, vmm, host, time_zone, cost, costPerMem,
				costPerStorage, costPerBw);

		FogDevice fogdevice = null;
		try {
			fogdevice = new FogDevice(nodeName, characteristics, 
					new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		fogdevice.setLevel(level);
		return fogdevice;
	}
	
	private static ClusterFogDevice createClusterFogDevice(String nodeName, long mips,
			int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower) {
		
		List<Pe> peList = new ArrayList<Pe>();

		// 3. Create PEs and add these into a list.
		peList.add(new Pe(0, new PeProvisionerOverbooking(mips))); // need to store Pe id and MIPS Rating

		int hostId = FogUtils.generateEntityId();
		long storage = 1000000; // host storage
		int bw = 10000;

		PowerHost host = new PowerHost(
				hostId,
				new RamProvisionerSimple(ram),
				new BwProvisionerOverbooking(bw),
				storage,
				peList,
				new StreamOperatorScheduler(peList),
				new FogLinearPowerModel(busyPower, idlePower)
			);

		List<Host> hostList = new ArrayList<Host>();
		hostList.add(host);

		String arch = "x86"; // system architecture
		String os = "Linux"; // operating system
		String vmm = "Xen";
		double time_zone = 10.0; // time zone this resource located
		double cost = 3.0; // the cost of using processing in this resource
		double costPerMem = 0.05; // the cost of using memory in this resource
		double costPerStorage = 0.001; // the cost of using storage in this
										// resource
		double costPerBw = 0.0; // the cost of using bw in this resource
		LinkedList<Storage> storageList = new LinkedList<Storage>(); // we are not adding SAN
													// devices by now

		FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
				arch, os, vmm, host, time_zone, cost, costPerMem,
				costPerStorage, costPerBw);

		ClusterFogDevice fogdevice = null;
		try {
			fogdevice = new ClusterFogDevice(nodeName, characteristics, 
					new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		fogdevice.setLevel(level);
		return fogdevice;
	}

	private static GWFogDevice createGWFogDevice(String nodeName, long mips,
			int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower, List<Integer> clusterFogDevicesIds) {
		
		List<Pe> peList = new ArrayList<Pe>();

		// 3. Create PEs and add these into a list.
		peList.add(new Pe(0, new PeProvisionerOverbooking(mips))); // need to store Pe id and MIPS Rating

		int hostId = FogUtils.generateEntityId();
		long storage = 1000000; // host storage
		int bw = 10000;

		PowerHost host = new PowerHost(
				hostId,
				new RamProvisionerSimple(ram),
				new BwProvisionerOverbooking(bw),
				storage,
				peList,
				new StreamOperatorScheduler(peList),
				new FogLinearPowerModel(busyPower, idlePower)
			);

		List<Host> hostList = new ArrayList<Host>();
		hostList.add(host);

		String arch = "x86"; // system architecture
		String os = "Linux"; // operating system
		String vmm = "Xen";
		double time_zone = 10.0; // time zone this resource located
		double cost = 3.0; // the cost of using processing in this resource
		double costPerMem = 0.05; // the cost of using memory in this resource
		double costPerStorage = 0.001; // the cost of using storage in this
										// resource
		double costPerBw = 0.0; // the cost of using bw in this resource
		LinkedList<Storage> storageList = new LinkedList<Storage>(); // we are not adding SAN
													// devices by now

		FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
				arch, os, vmm, host, time_zone, cost, costPerMem,
				costPerStorage, costPerBw);

		GWFogDevice fogdevice = null;
		try {
			fogdevice = new GWFogDevice(nodeName, characteristics, 
					new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips, clusterFogDevicesIds);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		fogdevice.setLevel(level);
		return fogdevice;
	}
	
	private static Application createApplication(String appId, int userId) {
		Application application = Application.createApplication(appId, userId);
		
		int mips = 300;
		int ram = 100;
		int size = 1000;
		for (int i = 0; i < numberOfSensorTypes; i++) {
			application.addAppModule("m" + (i + 1), ram, mips, 1000, 100);
			
			mips = mips + 100;
			ram = ram + 200;
			size = size + 100;
			
			application.addAppEdge("T" + (i + 1), "m" + (i + 1), mips * 3, 50, "T" + (i + 1), Tuple.UP, AppEdge.SENSOR);

			application.addAppEdge("m" + (i + 1), "A1", mips * 3, 50, "A1", Tuple.DOWN, AppEdge.ACTUATOR);
			
			application.addTupleMapping("m" + (i + 1), "T" + (i + 1), "A1", new FractionalSelectivity(1.0));
		}

		
		List<AppLoop> loops = new ArrayList<AppLoop>();
		for (int i = 0; i < numberOfSensorTypes; i++) {
			ArrayList<String> loop = new ArrayList<String>();
			loop.add("T" + (i + 1));
			loop.add("m" + (i + 1));
			loop.add("A1");
			loops.add(new AppLoop(loop));
		}

		application.setLoops(loops);
		
		return application;
	}
}
