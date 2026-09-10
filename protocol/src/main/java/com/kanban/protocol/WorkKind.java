package com.kanban.protocol;

/** What a work unit actually is, which is all the worker needs to know about it. */
public enum WorkKind {

    /** One board task — a card on the kanban. */
    TASK,

    /** One node of one workflow execution. */
    WORKFLOW_NODE
}
