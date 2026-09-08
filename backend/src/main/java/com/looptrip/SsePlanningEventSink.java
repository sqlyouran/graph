package com.looptrip;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class SsePlanningEventSink implements PlanningEventSink {
    private final PlanningSessionStore store; private final ThreadLocal<PlanningSession> current=new ThreadLocal<>();
    private final ThreadLocal<Integer> currentRound = ThreadLocal.withInitial(() -> 0);
    public SsePlanningEventSink(PlanningSessionStore store){this.store=store;}
    public <T> T forSession(PlanningSession s, Supplier<T> action){ current.set(s); try{return action.get();} finally{current.remove();currentRound.remove();} }
    @Override
    public <T> Callable<T> propagate(Callable<T> task){
        PlanningSession s=current.get();
        if(s==null)return task;
        int round=currentRound.get();
        return ()->{ current.set(s); currentRound.set(round);
            try{return task.call();} finally{current.remove();currentRound.remove();} };
    }
    public <T> T capture(List<PlanningEvent> events,Supplier<T> action){return action.get();}
    public void setRound(int round){currentRound.set(round);}
    public void emit(PlanningEventType type,String message,Map<String,Object> details){
        var s=current.get(); if(s==null)return;
        Map<String,Object> safe=new LinkedHashMap<>();
        for(String k:List.of("elapsedMs","model","passed","tool","origin","destination","date",
                "resultCount","summary","candidates","selectedOutboundFlight","selectedReturnFlight",
                "selectedHotels","selectedAttractions","contractProblemCount","hardFailureCount",
                "constraintResults","version","bestVersion","hasBestPlan","requestDiff",
                "action","slots","echo","decision",
                "includedSections","droppedSections","estimatedTokens","usableBudget",
                                "candidateId","field","sessionCount","condition","content","accepted","forgetReason","decision")) {
            if(details.containsKey(k)) safe.put(k, details.get(k));
        }
        if(type==PlanningEventType.REVIEW_COMPLETED && details.containsKey("problems")) {
            safe.put("problemsCount", ((List<?>)details.get("problems")).size());
        }
        if(type != PlanningEventType.HEARTBEAT) {
            store.publish(s,new PlanningSessionStore.PublicEvent(currentRound.get(),type,message,safe));
        }
    }
}
