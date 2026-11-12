package smo;

import java.util.ArrayList;
import java.util.List;

/** No JUnit required; run through ./test.sh */
public final class SimulationSelfTest {
    private static int checks=0;
    private static void ok(boolean condition,String message) {
        checks++;
        if(!condition) throw new AssertionError(message);
    }
    private static Simulation.Request request(int id,int source,int seq,double t) {
        return new Simulation.Request(id,source,seq,t);
    }
    private static void testRingBuffer() {
        Simulation.RingBuffer b=new Simulation.RingBuffer(3);
        Simulation.Request r1=request(1,1,1,1),r2=request(2,2,1,2),r3=request(3,1,2,3);
        ok(b.putRequest(r1,1)==0,"first ring cell");
        ok(b.putRequest(r2,2)==1,"second ring cell");
        ok(b.putRequest(r3,3)==2,"third ring cell");
        ok(b.isFull(),"full ring");
        ok(b.getWritePointer()==0,"ring wrap");
        ok(b.findNewestSlot()==2,"last buffered chosen for displacement");
        Simulation.Request dropped=b.displaceNewest(2);
        ok(dropped==r3 && dropped.getStatus()==Simulation.RequestStatus.DROPPED_D1OO4,"oldest not dropped");
        Simulation.Request replacement=request(4,2,2,4);
        b.writeReplacement(2,replacement,4);
        ok(b.getWritePointer()==0,"write pointer after replacement");
        ok(b.extractByRing()==r1,"ring read 1");
        ok(b.getReadPointer()==1,"read pointer after slot 1");
        ok(b.extractByRing()==r2,"ring read 2");
        ok(b.getReadPointer()==2,"read pointer after slot 2");
        ok(b.extractByRing()==replacement,"ring read replacement");
        ok(b.isEmpty() && b.getReadPointer()==0,"read pointer wrap");
        ok(b.putRequest(request(5,1,3,5),5)==0,"put after read wrap");
        ok(b.putRequest(request(6,1,4,6),6)==1,"put to next free");
        ok(b.extractByRing()!=null,"read first occupied from pointer");
        ok(b.putRequest(request(7,1,5,7),7)==2,"skip occupied, find free");
    }
    private static Simulation run(Simulation.Config cfg) {
        Simulation simulation=new Simulation(cfg);
        int steps=0;
        while(simulation.step()) {
            steps++;
            if(steps>100000) throw new AssertionError("Possible infinite loop");
            simulation.checkInvariants();
        }
        ok(simulation.isFinished(),"engine terminates");
        return simulation;
    }
    private static void testNoLossLowLoad() {
        Simulation sim=run(new Simulation.Config(1,2,3,0.1,0.01,0.01,10,17));
        Simulation.StatisticsCollector s=sim.getStatistics();
        ok(s.getTotalGenerated()==10,"all requested generated");
        ok(s.getTotalDropped()==0,"no losses at low load");
        ok(s.getTotalCompleted()==10,"every request serviced");
        ok(sim.getBuffer().size()==0,"buffer drained");
        ok(sim.getManager().busyCount()==0,"devices drained");
    }
    private static void testDisplacementAndIntermediateSteps() {
        Simulation sim=new Simulation(new Simulation.Config(2,1,1,10,20,20,25,19));
        boolean sawDisplace=false,sawWrite=false,sawSelect=false,sawStart=false;
        int previous=0;
        while(sim.step()) {
            ok(sim.getStepNumber()==previous+1,"one click = one event");
            previous++;
            Simulation.EventType type=sim.getLastEvent().getType();
            sawDisplace |= type==Simulation.EventType.DISPLACE_REQUEST;
            sawWrite |= type==Simulation.EventType.WRITE_REPLACEMENT;
            sawSelect |= type==Simulation.EventType.SELECT_BUFFER;
            sawStart |= type==Simulation.EventType.START_SERVICE;
            sim.checkInvariants();
        }
        ok(sawDisplace,"D1OO4 displacement step seen");
        ok(sawWrite,"new request written in a separate step");
        ok(sawSelect,"D2B3 selection step seen");
        ok(sawStart,"separate service-start step seen");
        ok(sim.getStatistics().getTotalDropped()>0,"some requests displaced");
        ok(sim.getStatistics().getTotalGenerated()==25,"correct generation limit");
        ok(sim.getStatistics().getTotalGenerated()==sim.getStatistics().getTotalDropped()+sim.getStatistics().getTotalCompleted(),"requests conserved at end");
    }
    private static List<String> trace(Simulation.Config cfg) {
        Simulation sim = new Simulation(cfg);
        List<String> results = new ArrayList<>();
        while (sim.step()) {
            results.add(sim.getLastEvent().getType() + " | " + sim.getTime() + " | " + sim.getLastDescription());
        }
        return results;
    }
    private static void testDeterminism() {
        Simulation.Config cfg=new Simulation.Config(3,2,4,1.2,1.0,2.0,40,17);
        Simulation a=run(cfg);
        Simulation b=run(cfg);
        ok(a.getStepNumber()==b.getStepNumber(),"steps deterministic");
        ok(a.getStatistics().getTotalDropped()==b.getStatistics().getTotalDropped(),"losses deterministic");
        ok(a.getStatistics().getTotalCompleted()==b.getStatistics().getTotalCompleted(),"completed deterministic");
        ok(trace(cfg).equals(trace(cfg)),"events deterministic");
    }
    public static void main(String[] args) {
        testRingBuffer(); testNoLossLowLoad(); testDisplacementAndIntermediateSteps(); testDeterminism();
        System.out.println("PASS: "+checks+" checks (ring buffer, overflow, steps, termination, determinism)");
    }
}
