package com.kanban.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** The workflows themselves, newest first for the list panel. */
public interface WorkflowRepository extends JpaRepository<Workflow, String> {

    List<Workflow> findAllByOrderByCreatedAtDesc();
}
