package com.kanban.work;

import com.kanban.protocol.SessionLimitsDto;

import java.util.List;

/**
 * How one workflow node's session must be run: the extra CLI flags it gets and the clocks it
 * runs under. Computed on the server so a worker applies them without needing to know why they
 * differ between steps.
 */
public record NodeSessionSpec(List<String> sessionArgs, SessionLimitsDto limits) {
}
