package com.virco.agent.react;

public sealed interface Event permits Event.StepStart, Event.Delta, Event.ToolStart,
        Event.ToolDone, Event.Done, Event.Error {
    record StepStart(int step) implements Event {}
    record Delta(String content) implements Event {}
    record ToolStart(String name, String args) implements Event {}
    record ToolDone(String name, long elapsedMs, String resultHead) implements Event {}
    record Done(long elapsedMs, int steps, String answer) implements Event {}
    record Error(String code, String message) implements Event {}
    public interface EventSink { void emit(Event e); }
}