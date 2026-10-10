package com.forgeflow.intelligence;

import com.forgeflow.billing.Entitlements;
import com.forgeflow.billing.Quota;
import com.forgeflow.billing.UsageKind;
import com.forgeflow.billing.UsageMeter;
import com.forgeflow.shared.llm.ImagePart;
import com.forgeflow.shared.llm.LlmClient;
import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.LlmResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * "AI checks its own app": a vision model looks at a screenshot of the running
 * preview and says whether it matches what the user asked for.
 *
 * The other two self-checks can't see this class of failure. The build gate
 * proves the files parse and link; the console bridge catches what throws at
 * runtime. Neither notices white text on a white button, a layout that
 * collapsed into one column of overlapping boxes, or a "pricing table" with
 * no prices in it. Those only show up when something LOOKS at the page.
 *
 * The screenshot is taken in the visitor's browser (the preview's bridge
 * script renders the page to a canvas), so the server needs no headless
 * Chrome: zero extra infrastructure, and the review sees exactly what the
 * user sees, at the size they see it.
 *
 * One model call, no tools, a small JSON verdict. Metered like any other call.
 */
@Component
public class VisualReviewer {

    public static final String LOOKS_RIGHT = "LOOKS_RIGHT";
    public static final String NEEDS_FIXES = "NEEDS_FIXES";
    static final int MAX_ISSUES = 5;
    static final int MAX_REQUEST_CHARS = 600;

    /** DemoLlmClient recognises this opening line; keep them in step. */
    public static final String SYSTEM = """
            You review a screenshot of a web app that an AI just built, the way a careful
            front-end developer would before showing it to the person who asked for it.

            Judge ONLY what you can see, against what the user asked for. Look for:
            - something they asked for that is missing or obviously wrong
            - text that is cut off, overlapping, or unreadable (contrast, size)
            - a broken or collapsed layout, elements spilling off screen, a blank page
            - controls that look unusable (invisible buttons, inputs with no labels)
            Do not nitpick taste. Colours, fonts and spacing that are merely not to your
            liking are not issues. If it does the job and looks intentional, say so.

            severity "major": the user would notice and be disappointed (missing feature,
            broken layout, unreadable text). "minor": a polish item.

            Reply with ONLY this JSON, no prose around it:
            {"score": 1-10, "verdict": "looks_right" | "needs_fixes",
             "summary": "one sentence a non-developer understands",
             "issues": [{"severity": "major" | "minor", "text": "what is wrong and where, specific enough to fix"}]}
            Use "needs_fixes" only when there is at least one major issue. At most 5 issues.""";

    public record Issue(String severity, String text) {
    }

    public record Verdict(int score, String verdict, String summary, List<Issue> issues, int tokensUsed) {
        public boolean hasMajor() {
            return issues.stream().anyMatch(i -> "major".equals(i.severity()));
        }
    }

    /** The model answered, but not with anything we could read as a verdict. */
    public static class UnreadableReview extends RuntimeException {
        UnreadableReview(String message) {
            super(message);
        }
    }

    private final LlmClient llm;
    private final Entitlements entitlements;
    private final UsageMeter usage;
    private final ObjectMapper json = new ObjectMapper();

    public VisualReviewer(LlmClient llm, Entitlements entitlements, UsageMeter usage) {
        this.llm = llm;
        this.entitlements = entitlements;
        this.usage = usage;
    }

    /**
     * @param requests what the user asked for in this conversation, oldest first; the last one is
     *                 the change this screenshot should show
     * @throws com.forgeflow.shared.QuotaExceededException (402) when the caller is out of AI tokens today
     * @throws UnreadableReview when the reply can't be parsed (tokens are still recorded - they were spent)
     */
    public Verdict review(Long userId, Long projectId, List<String> requests, ImagePart screenshot) {
        entitlements.requireRoomFor(userId, Quota.AI_TOKENS_PER_DAY);
        LlmResponse r = llm.chat(SYSTEM, List.of(LlmMessage.user(prompt(requests), List.of(screenshot))), List.of());
        usage.record(userId, projectId, UsageKind.AI_TOKENS, r.totalTokens(), "visual-review");
        return parse(r.text(), r.totalTokens());
    }

    static String prompt(List<String> requests) {
        StringBuilder sb = new StringBuilder("What the user asked for in this conversation, oldest first:\n");
        for (int i = 0; i < requests.size(); i++) {
            String q = requests.get(i).strip();
            if (q.length() > MAX_REQUEST_CHARS) {
                q = q.substring(0, MAX_REQUEST_CHARS) + "...";
            }
            sb.append(i == requests.size() - 1 ? "LATEST REQUEST: " : (i + 1) + ". ").append(q).append('\n');
        }
        return sb.append("\nThe screenshot shows the app as it renders now, after the latest request.").toString();
    }

    /**
     * Models wrap JSON in code fences, add a sentence before it, or say
     * "needs_fixes" with nothing major. The verdict follows the issues, not
     * the label: a review that lists a major issue needs fixes, whatever it
     * called itself, and one that lists none looks right.
     */
    Verdict parse(String text, int tokens) {
        if (text == null) {
            throw new UnreadableReview("empty review");
        }
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close <= open) {
            throw new UnreadableReview("no JSON object in the review");
        }
        JsonNode root;
        try {
            root = json.readTree(text.substring(open, close + 1));
        } catch (RuntimeException e) {
            throw new UnreadableReview("review is not valid JSON");
        }
        List<Issue> issues = new ArrayList<>();
        for (JsonNode n : root.path("issues")) {
            String issue = n.isTextual() ? n.asText() : n.path("text").asText("");
            if (issue.isBlank() || issues.size() == MAX_ISSUES) {
                continue;
            }
            String severity = "major".equalsIgnoreCase(n.path("severity").asText("")) ? "major" : "minor";
            issues.add(new Issue(severity, issue.strip()));
        }
        boolean major = issues.stream().anyMatch(i -> "major".equals(i.severity()));
        int score = root.path("score").asInt(major ? 5 : 8);
        score = Math.max(1, Math.min(10, score));
        String summary = root.path("summary").asText("").strip();
        if (summary.isEmpty()) {
            summary = major ? "Some things need fixing." : "Looks like what was asked for.";
        }
        return new Verdict(score, major ? NEEDS_FIXES : LOOKS_RIGHT, summary, List.copyOf(issues), tokens);
    }
}
