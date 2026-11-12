package smo;

import java.util.*;

/** Discrete-event simulation for variant 17: ИБ ИЗ1 ПЗ2 Д1ОЗ1 Д1ОО4 Д2П1 Д2Б3 ОР1 ОД2. */
public final class Simulation {
    public record Config(int sources, int devices, int bufferSize, double lambda,
                         double serviceMin, double serviceMax, int maxRequests, long seed) {
        public Config {
            if (sources < 1 || devices < 1 || bufferSize < 1 || maxRequests < 1)
                throw new IllegalArgumentException("Количества источников, приборов, мест и заявок должны быть > 0");
            if (!Double.isFinite(lambda) || lambda <= 0)
                throw new IllegalArgumentException("Интенсивность lambda должна быть > 0");
            if (!Double.isFinite(serviceMin) || !Double.isFinite(serviceMax)
                    || serviceMin <= 0 || serviceMax < serviceMin)
                throw new IllegalArgumentException("Время обслуживания: 0 < min <= max");
        }
    }

    public enum RequestStatus { GENERATED, BUFFERED, IN_SERVICE, COMPLETED, DROPPED_D1OO4 }
    public enum EventType {
        GENERATE_REQUEST("Генерация заявки"),
        ROUTE_REQUEST("Диспетчер постановки"),
        PUT_BUFFER("Постановка в буфер"),
        DISPLACE_REQUEST("Выбивание из буфера"),
        WRITE_REPLACEMENT("Замена заявки в буфере"),
        SELECT_BUFFER("Выбор заявки из буфера"),
        START_SERVICE("Постановка на прибор"),
        SERVICE_FINISH("Завершение обслуживания");
        public final String title;
        EventType(String title) { this.title = title; }
    }

    public static final class Request {
        private final int id, sourceId, sourceSequence;
        private final double generationTime;
        private double bufferEntryTime = Double.NaN;
        private double serviceStartTime = Double.NaN;
        private double serviceEndTime = Double.NaN;
        private RequestStatus status = RequestStatus.GENERATED;

        Request(int id, int sourceId, int sequence, double generationTime) {
            this.id = id; this.sourceId = sourceId;
            this.sourceSequence = sequence; this.generationTime = generationTime;
        }
        public String label() { return sourceId + "." + sourceSequence; }
        public int getId() { return id; }
        public int getSourceId() { return sourceId; }
        public double getGenerationTime() { return generationTime; }
        public double getBufferEntryTime() { return bufferEntryTime; }
        public double getServiceStartTime() { return serviceStartTime; }
        public double getServiceEndTime() { return serviceEndTime; }
        public RequestStatus getStatus() { return status; }
        public double waitTime() { return serviceStartTime - generationTime; }
        public double serviceTime() { return serviceEndTime - serviceStartTime; }
        public double totalTime() { return serviceEndTime - generationTime; }
    }

    public static final class Event implements Comparable<Event> {
        private final EventType type;
        private final double time;
        private final int sourceId, deviceId, slot;
        private final Request request;
        private final long serial;

        Event(EventType type, double time, int sourceId, int deviceId, int slot,
              Request request, long serial) {
            this.type=type; this.time=time; this.sourceId=sourceId;
            this.deviceId=deviceId; this.slot=slot; this.request=request; this.serial=serial;
        }
        private int phase() {
            // The internal steps are completed before external events of equal timestamp.
            // If several external events coincide, finish service before generation.
            return switch (type) {
                case GENERATE_REQUEST -> 2;
                case SERVICE_FINISH -> 1;
                default -> 0;
            };
        }
        @Override public int compareTo(Event other) {
            int c = Double.compare(time, other.time);
            if (c != 0) return c;
            c = Integer.compare(phase(), other.phase());
            return c != 0 ? c : Long.compare(serial, other.serial);
        }
        public EventType getType() { return type; }
        public double getTime() { return time; }
        public String subject() {
            if (request != null) return "Заявка " + request.label();
            if (sourceId > 0) return "И" + sourceId;
            if (deviceId > 0) return "П" + deviceId;
            return "—";
        }
    }

    public static final class Source {
        private final int id;
        private final double lambda;
        private int generatedCount = 0;
        private double nextGenerationTime = Double.NaN;
        Source(int id, double lambda) { this.id = id; this.lambda = lambda; }
        public int getId() { return id; }
        public int getGeneratedCount() { return generatedCount; }
        public double getNextGenerationTime() { return nextGenerationTime; }
        Request generateRequest(int globalId, double currentTime) {
            return new Request(globalId, id, ++generatedCount, currentTime);
        }
        double calculateNextInterval(Random random) {
            return -Math.log(1.0 - random.nextDouble()) / lambda;
        }
    }

    public static final class RingBuffer {
        private final Request[] slots;
        private int writePointer = 0;
        private int readPointer = 0;
        private int size = 0;

        RingBuffer(int capacity) { slots = new Request[capacity]; }
        public int capacity() { return slots.length; }
        public int size() { return size; }
        public int getWritePointer() { return writePointer; }
        public int getReadPointer() { return readPointer; }
        public boolean isFull() { return size == slots.length; }
        public boolean isEmpty() { return size == 0; }
        public Request get(int index) { return slots[index]; }

        /** Д1ОЗ1: search for first free cell from writePointer, move pointer after insert. */
        public int putRequest(Request request, double time) {
            if (isFull()) throw new IllegalStateException("Буфер полон");
            for (int offset=0; offset<slots.length; offset++) {
                int index = (writePointer + offset) % slots.length;
                if (slots[index] == null) { insertAt(index, request, time); return index; }
            }
            throw new IllegalStateException("В буфере должна быть свободная ячейка");
        }
        private void insertAt(int index, Request request, double time) {
            if (slots[index] != null) throw new IllegalStateException("Занятая ячейка");
            slots[index] = request; size++;
            request.status = RequestStatus.BUFFERED;
            request.bufferEntryTime = time;
            writePointer = (index + 1) % slots.length;
        }
        public void writeReplacement(int index, Request request, double time) {
            insertAt(index, request, time);
        }
        /** Д1ОО4: remove latest buffered request, not the incoming one. */
        public int findNewestSlot() {
            int candidate = -1;
            for (int i=0;i<slots.length;i++) {
                if (slots[i] != null && (candidate == -1
                        || slots[i].bufferEntryTime > slots[candidate].bufferEntryTime
                        || (slots[i].bufferEntryTime == slots[candidate].bufferEntryTime
                            && slots[i].id > slots[candidate].id))) candidate=i;
            }
            return candidate;
        }
        public Request displaceNewest(int index) {
            if (!isFull() || index != findNewestSlot()) throw new IllegalStateException("Неверное выбивание");
            Request displaced = slots[index];
            slots[index] = null; size--;
            displaced.status = RequestStatus.DROPPED_D1OO4;
            return displaced;
        }
        /** Д2Б3: search from readPointer, remove and advance cyclically. */
        public Request extractByRing() {
            if (isEmpty()) return null;
            for (int offset=0;offset<slots.length;offset++) {
                int index = (readPointer+offset) % slots.length;
                if (slots[index] != null) {
                    Request result = slots[index];
                    slots[index]=null; size--;
                    readPointer=(index+1)%slots.length;
                    return result;
                }
            }
            throw new IllegalStateException("Нарушен счетчик занятых мест");
        }
    }

    public static final class Device {
        private final int id;
        private Request currentRequest;
        private double finishTime = Double.NaN;
        private double busyTotal = 0;
        private int servicedCount = 0;
        Device(int id) { this.id=id; }
        public int getId() { return id; }
        public Request getCurrentRequest() { return currentRequest; }
        public double getFinishTime() { return finishTime; }
        public int getServicedCount() { return servicedCount; }
        public boolean isBusy() { return currentRequest != null; }
        public double utilization(double time) {
            if (time <= 0) return 0;
            double current = isBusy() ? Math.max(0, time-currentRequest.serviceStartTime) : 0;
            return Math.min(1, (busyTotal+current)/time);
        }
        void start(Request request, double start, double duration) {
            if (isBusy()) throw new IllegalStateException("Прибор занят");
            currentRequest = request; finishTime = start+duration;
            request.serviceStartTime=start; request.status=RequestStatus.IN_SERVICE;
        }
        Request finish(double time) {
            if (!isBusy() || Math.abs(finishTime-time)>1e-8)
                throw new IllegalStateException("Неверное завершение обслуживания");
            Request result=currentRequest;
            result.serviceEndTime=time; result.status=RequestStatus.COMPLETED;
            busyTotal+=time-result.serviceStartTime; servicedCount++;
            currentRequest=null; finishTime=Double.NaN;
            return result;
        }
    }

    public static final class DeviceManager {
        private final Device[] devices;
        DeviceManager(int count) {
            devices=new Device[count];
            for (int i=0;i<count;i++) devices[i]=new Device(i+1);
        }
        /** Д2П1: the first available device by number. */
        public Device getFreeDeviceByPriority() {
            for (Device device:devices) if (!device.isBusy()) return device;
            return null;
        }
        public Device get(int id) { return devices[id-1]; }
        public List<Device> all() { return List.of(devices); }
        public int busyCount() {
            int count=0; for(Device d:devices) if(d.isBusy()) count++;
            return count;
        }
    }

    public static final class StatisticsCollector {
        private final int[] generated, dropped, completed;
        private final double[] waitTotal, serviceTotal, systemTotal, waitSquared, serviceSquared;
        private int totalGenerated = 0, totalDropped = 0, totalCompleted = 0;
        StatisticsCollector(int sources) {
            generated=new int[sources]; dropped=new int[sources]; completed=new int[sources];
            waitTotal=new double[sources]; serviceTotal=new double[sources];
            systemTotal=new double[sources];
            waitSquared=new double[sources]; serviceSquared=new double[sources];
        }
        void recordRequestGenerated(Request request) {
            generated[request.sourceId-1]++; totalGenerated++;
        }
        void recordRequestDropped(Request request) {
            dropped[request.sourceId-1]++; totalDropped++;
        }
        void recordServiceFinished(Request request) {
            int index=request.sourceId-1;
            completed[index]++; totalCompleted++;
            waitTotal[index]+=request.waitTime();
            serviceTotal[index]+=request.serviceTime();
            systemTotal[index]+=request.totalTime();
            waitSquared[index]+=request.waitTime()*request.waitTime();
            serviceSquared[index]+=request.serviceTime()*request.serviceTime();
        }
        public int getGeneratedCount(int index) { return generated[index]; }
        public int getDroppedCount(int index) { return dropped[index]; }
        public int getCompletedCount(int index) { return completed[index]; }
        public int getTotalGenerated() { return totalGenerated; }
        public int getTotalDropped() { return totalDropped; }
        public int getTotalCompleted() { return totalCompleted; }
        public double avgWait(int index) { return completed[index]==0 ? 0 : waitTotal[index]/completed[index]; }
        public double avgService(int index) { return completed[index]==0 ? 0 : serviceTotal[index]/completed[index]; }
        public double avgSystem(int index) { return completed[index]==0 ? 0 : systemTotal[index]/completed[index]; }
        public double waitVariance(int index) {
            return completed[index]==0 ? 0 : Math.max(0,waitSquared[index]/completed[index]-Math.pow(avgWait(index),2));
        }
        public double serviceVariance(int index) {
            return completed[index]==0 ? 0 : Math.max(0,serviceSquared[index]/completed[index]-Math.pow(avgService(index),2));
        }
        public double failurePercent() {
            return totalGenerated==0 ? 0 : totalDropped*100.0/totalGenerated;
        }
        public double failurePercent(int index) {
            return generated[index]==0 ? 0 : dropped[index]*100.0/generated[index];
        }
    }

    public final class PutDispatcher {
        private final RingBuffer buffer;
        private final DeviceManager manager;
        private final StatisticsCollector statistics;
        PutDispatcher(RingBuffer buffer, DeviceManager manager, StatisticsCollector statistics) {
            this.buffer=buffer; this.manager=manager; this.statistics=statistics;
        }
        void putRequest(Request request) {
            Device free=manager.getFreeDeviceByPriority();
            if (free!=null) {
                schedule(EventType.START_SERVICE, time, 0, free.id, -1, request);
                lastDescription="ДП: свободен П"+free.id+" (Д2П1); заявка "+request.label()+" направлена на обслуживание";
            } else {
                schedule(EventType.PUT_BUFFER, time, 0, 0, -1, request);
                lastDescription="ДП: все приборы заняты; заявка "+request.label()+" направлена в буфер";
            }
        }
        void bufferRequest(Request request) {
            if (!buffer.isFull()) {
                int slot=buffer.putRequest(request, time);
                lastDescription="Д1ОЗ1: заявка "+request.label()+" записана в Б"+(slot+1);
            } else {
                int slot=buffer.findNewestSlot();
                schedule(EventType.DISPLACE_REQUEST, time, 0, 0, slot, request);
                lastDescription="ДП: буфер полон; по Д1ОО4 будет выбита последняя помещенная заявка из Б"+(slot+1);
            }
        }
        void displace(Request incoming, int slot) {
            Request discarded=buffer.displaceNewest(slot);
            statistics.recordRequestDropped(discarded);
            schedule(EventType.WRITE_REPLACEMENT, time, 0, 0, slot, incoming);
            lastDescription="Д1ОО4: выбита заявка "+discarded.label()+" из Б"+(slot+1)+"; отказ учтен";
        }
        void putReplacement(Request incoming,int slot) {
            buffer.writeReplacement(slot,incoming,time);
            lastDescription="Д1ОО4: на место Б"+(slot+1)+" записана новая заявка "+incoming.label();
        }
    }

    public final class SelectDispatcher {
        private final RingBuffer buffer;
        private final DeviceManager manager;
        SelectDispatcher(RingBuffer buffer, DeviceManager manager) {
            this.buffer=buffer; this.manager=manager;
        }
        void selectAfterRelease() {
            Device free=manager.getFreeDeviceByPriority();
            if (free==null || buffer.isEmpty()) {
                lastDescription="ДВ: " +(buffer.isEmpty()?"буфер пуст":"нет свободного прибора");
                return;
            }
            Request request=buffer.extractByRing();
            schedule(EventType.START_SERVICE, time, 0, free.id, -1, request);
            lastDescription="Д2Б3: выбрана заявка "+request.label()+" по указателю выборки; назначен П"+free.id;
        }
    }

    private final Config config;
    private final Random random;
    private final Source[] sources;
    private final RingBuffer buffer;
    private final DeviceManager manager;
    private final PriorityQueue<Event> eventQueue=new PriorityQueue<>();
    private final StatisticsCollector statistics;
    private final PutDispatcher putDispatcher;
    private final SelectDispatcher selectDispatcher;
    private int nextRequestId=1;
    private long nextEventSerial=0;
    private int stepNumber=0;
    private double time=0;
    private String lastDescription="Начальное состояние системы";
    private Event lastEvent;

    public Simulation(Config config) {
        this.config=config; this.random=new Random(config.seed());
        sources=new Source[config.sources()];
        for(int i=0;i<sources.length;i++) sources[i]=new Source(i+1,config.lambda());
        buffer=new RingBuffer(config.bufferSize());
        manager=new DeviceManager(config.devices());
        statistics=new StatisticsCollector(config.sources());
        putDispatcher=new PutDispatcher(buffer,manager,statistics);
        selectDispatcher=new SelectDispatcher(buffer,manager);
        for(Source source:sources) {
            // For Poisson sources first arrival also has an exponential delay.
            double first=source.calculateNextInterval(random);
            source.nextGenerationTime=first;
            schedule(EventType.GENERATE_REQUEST,first,source.id,0,-1,null);
        }
    }

    private void schedule(EventType type,double at,int sourceId,int deviceId,int slot,Request request) {
        if (!Double.isFinite(at) || at<time)
            throw new IllegalArgumentException("Некорректное время события: "+at);
        eventQueue.add(new Event(type,at,sourceId,deviceId,slot,request,nextEventSerial++));
    }

    /** Advances EXACTLY ONE operation/event, including all internal actions at the same simulation time. */
    public boolean step() {
        Event event=eventQueue.poll();
        if(event==null) return false;
        if(event.time+1e-12<time) throw new IllegalStateException("Время пошло назад");
        time=event.time; lastEvent=event; stepNumber++;
        switch(event.type) {
            case GENERATE_REQUEST -> {
                Source source=sources[event.sourceId-1];
                Request request=source.generateRequest(nextRequestId++,time);
                source.nextGenerationTime=Double.NaN;
                statistics.recordRequestGenerated(request);
                schedule(EventType.ROUTE_REQUEST,time,source.id,0,-1,request);
                lastDescription="И"+source.id+": сгенерирована заявка "+request.label()+" (ИБ, ИЗ1)";
                if (statistics.totalGenerated>=config.maxRequests()) {
                    eventQueue.removeIf(e -> e.type == EventType.GENERATE_REQUEST);
                    for(Source s:sources) s.nextGenerationTime=Double.NaN;
                } else {
                    double next=time+source.calculateNextInterval(random);
                    source.nextGenerationTime=next;
                    schedule(EventType.GENERATE_REQUEST,next,source.id,0,-1,null);
                }
            }
            case ROUTE_REQUEST -> putDispatcher.putRequest(event.request);
            case PUT_BUFFER -> putDispatcher.bufferRequest(event.request);
            case DISPLACE_REQUEST -> putDispatcher.displace(event.request,event.slot);
            case WRITE_REPLACEMENT -> putDispatcher.putReplacement(event.request,event.slot);
            case SELECT_BUFFER -> selectDispatcher.selectAfterRelease();
            case START_SERVICE -> {
                Device device=manager.get(event.deviceId);
                if(device.isBusy()) throw new IllegalStateException("Назначен занятый прибор");
                double duration=config.serviceMin()+random.nextDouble()*(config.serviceMax()-config.serviceMin());
                device.start(event.request,time,duration);
                schedule(EventType.SERVICE_FINISH,device.finishTime,0,device.id,-1,event.request);
                lastDescription="П"+device.id+": заявка "+event.request.label()
                        +" начала обслуживание (ПЗ2), завершение t="+fmt(device.finishTime);
            }
            case SERVICE_FINISH -> {
                Device device=manager.get(event.deviceId);
                Request completed=device.finish(time);
                if(completed != event.request) throw new IllegalStateException("Неверная заявка на приборе");
                statistics.recordServiceFinished(completed);
                lastDescription="П"+device.id+": заявка "+completed.label()+" обработана; прибор освобожден";
                if(!buffer.isEmpty()) schedule(EventType.SELECT_BUFFER,time,0,0,-1,null);
            }
        }
        checkInvariants();
        return true;
    }

    /** Stop after the last generated request has been completed or displaced. */
    public boolean isFinished() { return eventQueue.isEmpty(); }
    public Config getConfig(){return config;}
    public double getTime(){return time;}
    public int getStepNumber(){return stepNumber;}
    public String getLastDescription(){return lastDescription;}
    public Event getLastEvent(){return lastEvent;}
    public Source[] getSources(){return sources.clone();}
    public RingBuffer getBuffer(){return buffer;}
    public DeviceManager getManager(){return manager;}
    public StatisticsCollector getStatistics(){return statistics;}
    public static String fmt(double value){return Double.isNaN(value)?"—":String.format(Locale.ROOT,"%.3f",value);}

    public void checkInvariants() {
        int actualBuffered=0;
        for(int i=0;i<buffer.capacity();i++) {
            Request request=buffer.get(i);
            if(request!=null){actualBuffered++; if(request.status!=RequestStatus.BUFFERED)
                throw new IllegalStateException("Статус заявки в буфере неверен");}
        }
        if(actualBuffered!=buffer.size()) throw new IllegalStateException("Неверный размер буфера");
        if(statistics.totalCompleted+statistics.totalDropped+buffer.size()+manager.busyCount()
                > statistics.totalGenerated) throw new IllegalStateException("Нарушен баланс заявок");
        if(isFinished() && (buffer.size()!=0 || manager.busyCount()!=0
                || statistics.totalCompleted+statistics.totalDropped!=statistics.totalGenerated))
            throw new IllegalStateException("Неверный баланс при завершении моделирования");
    }
}
