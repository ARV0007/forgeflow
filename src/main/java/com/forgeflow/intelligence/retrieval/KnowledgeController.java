package com.forgeflow.intelligence.retrieval;

import com.forgeflow.workspace.Stack;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The knowledge base, readable: what notes there are, and the search the agent's search_docs runs. */
@RestController
public class KnowledgeController {

    private final KnowledgeBase knowledge;

    public KnowledgeController(KnowledgeBase knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/api/v1/knowledge")
    public List<Map<String, Object>> list() {
        return knowledge.list();
    }

    @GetMapping("/api/v1/knowledge/search")
    public List<KnowledgeBase.Hit> search(@RequestParam("q") String q,
                                         @RequestParam(defaultValue = "5") int k,
                                         @RequestParam(required = false) String stack) {
        Stack s = null;
        if (stack != null && !stack.isBlank()) {
            try {
                s = Stack.valueOf(stack.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stack must be static or react");
            }
        }
        return knowledge.search(q, k, s);
    }
}
