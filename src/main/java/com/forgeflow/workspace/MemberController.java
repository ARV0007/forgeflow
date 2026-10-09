package com.forgeflow.workspace;

import com.forgeflow.workspace.dto.AddMemberRequest;
import com.forgeflow.workspace.dto.MemberResponse;
import com.forgeflow.workspace.dto.UpdateMemberRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
public class MemberController {

    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    private static Long caller(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    @GetMapping
    public List<MemberResponse> list(@PathVariable Long projectId, Authentication auth) {
        return members.list(projectId, caller(auth));
    }

    @PostMapping
    public ResponseEntity<MemberResponse> add(@PathVariable Long projectId,
                                              @Valid @RequestBody AddMemberRequest req,
                                              Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(members.add(projectId, caller(auth), req.email(), req.role()));
    }

    @PatchMapping("/{userId}")
    public MemberResponse changeRole(@PathVariable Long projectId, @PathVariable Long userId,
                                     @Valid @RequestBody UpdateMemberRequest req, Authentication auth) {
        return members.changeRole(projectId, caller(auth), userId, req.role());
    }

    /** Removes a member - or, when userId is yourself, leaves the project. */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(@PathVariable Long projectId, @PathVariable Long userId,
                                       Authentication auth) {
        members.remove(projectId, caller(auth), userId);
        return ResponseEntity.noContent().build();
    }
}
