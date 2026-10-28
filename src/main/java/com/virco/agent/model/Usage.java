package com.virco.agent.model;

public record Usage(int prompt, int completion, int total) {
    public static final Usage ZERO = new Usage(0, 0, 0);
}