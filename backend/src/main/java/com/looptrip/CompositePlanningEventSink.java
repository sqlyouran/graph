package com.looptrip;
import java.util.*; import java.util.concurrent.Callable; import java.util.function.Supplier; import org.springframework.stereotype.Component; import org.springframework.context.annotation.Primary;
@Component @Primary
public class CompositePlanningEventSink implements PlanningEventSink {
 private final InMemoryPlanningEventSink memory; private final SsePlanningEventSink sse;
 public CompositePlanningEventSink(InMemoryPlanningEventSink memory,SsePlanningEventSink sse){this.memory=memory;this.sse=sse;}
 public <T>T capture(List<PlanningEvent> e,Supplier<T>a){return memory.capture(e,()->sse.capture(e,a));}
 public void setRound(int r){try{memory.setRound(r);}catch(RuntimeException ignored){} try{sse.setRound(r);}catch(RuntimeException ignored){}}
 public void emit(PlanningEventType t,String m,Map<String,Object>d){try{memory.emit(t,m,d);}catch(RuntimeException ignored){} try{sse.emit(t,m,d);}catch(RuntimeException ignored){}}
 @Override
 public <T> Callable<T> propagate(Callable<T> task){return sse.propagate(memory.propagate(task));}
}
