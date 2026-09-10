package com.kanban.workflow;

import com.kanban.workflow.WorkflowDtos.FavoriteStepView;
import com.kanban.workflow.WorkflowDtos.SaveFavoriteStepRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The shelf of steps kept aside to be used again — saved from one workflow's editor,
 * inserted into another's as a fresh node.
 *
 * <p>Deliberately its own service and its own table rather than a flag on
 * {@link WorkflowNode}: a favourite has to outlive the graph it was written in, and a
 * node row does not — saving a workflow replaces its nodes wholesale
 * ({@code WorkflowService#save}), so a starred node would be dropped by the next save
 * of the workflow it came from.
 */
@Service
public class FavoriteStepService {

    /**
     * How many steps may be on the shelf. The picker shows them all at once as a row
     * of chips, so this is really a limit on that row staying readable; nothing here
     * gets slow at any plausible number. Hitting it is a prompt to tidy up, and the
     * message says so rather than silently dropping the oldest — which favourite is
     * worth keeping is not a question this code can answer.
     */
    static final int MAX_FAVORITES = 100;

    private final FavoriteStepRepository favorites;

    public FavoriteStepService(FavoriteStepRepository favorites) {
        this.favorites = favorites;
    }

    public List<FavoriteStepView> list() {
        return favorites.findAllByOrderByNameAsc().stream().map(FavoriteStepView::from).toList();
    }

    /**
     * Save a step under its name, replacing whatever was under that name before.
     *
     * <p>An upsert rather than a plain insert because the name is how a user picks one
     * off the shelf: two favourites called "Summarise" are indistinguishable in the
     * picker, and the second one is nearly always the first one edited. The client
     * knows the existing names — it renders them — so it is the one placed to ask
     * before overwriting; this end just does what it is told.
     */
    @Transactional
    public FavoriteStepView save(SaveFavoriteStepRequest req) {
        String name = req.name().trim();
        if (name.isEmpty()) {
            throw badRequest("A favourite step needs a name.");
        }
        EffortLevel effort;
        try {
            effort = EffortLevel.parse(req.effort());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }

        Optional<FavoriteStep> existing = favorites.findFirstByNameIgnoreCase(name);
        if (existing.isEmpty() && favorites.count() >= MAX_FAVORITES) {
            throw badRequest("There are already " + MAX_FAVORITES + " favourite steps. "
                    + "Remove one you no longer use before saving another.");
        }

        FavoriteStep step = existing.orElseGet(
                () -> new FavoriteStep(UUID.randomUUID().toString(), name, null, null));
        // Re-set the name even when the row was found: the lookup ignores case, so
        // saving "Summarise" over "summarise" should leave the shelf showing the
        // spelling the user just used, not the one they typed the first time.
        step.setName(name);
        step.setPrompt(req.prompt());
        step.setEffort(effort);
        return FavoriteStepView.from(favorites.save(step));
    }

    @Transactional
    public void delete(String id) {
        if (!favorites.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such favourite step: " + id);
        }
        favorites.deleteById(id);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
