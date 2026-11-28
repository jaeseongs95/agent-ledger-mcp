// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.admin;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.veridger.database.repository.IssueRepository;
import org.veridger.database.repository.EntityLookupRepository;
import org.veridger.database.repository.ProjectRepository;
import org.veridger.database.repository.ProjectVersionRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.LookupException;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.TextRule;

@Service
@Transactional
public class ProjectVersionService {

    private static final int MAX_ISSUE_LINKS = 200;

    public record Version(String name, String description) {}

    public record Change(String projectId, String name, boolean create, String originalName, String description,
            List<String> linkIssueIds, List<String> unlinkIssueIds) {}

    public record Outcome(String id, String name, boolean created, List<String> linked, List<String> unlinked) {}

    private final ProjectRepository projects;
    private final IssueRepository issues;
    private final ProjectVersionRepository versions;
    private final EntityLookupRepository lookup;

    public ProjectVersionService(ProjectRepository projects, IssueRepository issues, ProjectVersionRepository versions,
            EntityLookupRepository lookup) {
        this.projects = projects;
        this.issues = issues;
        this.versions = versions;
        this.lookup = lookup;
    }

    @Transactional(readOnly = true)
    public List<Version> list(String project) {
        return versions.list(List.of(ProjectId.of(project).value())).stream()
                .map(row -> new Version(row.name(), row.description())).toList();
    }

    public void add(String rawProject, String rawName, String rawDescription) {
        ProjectId project = ProjectId.of(rawProject);
        String name = TextRule.PROJECT_VERSION_NAME.required(rawName);
        String description = TextRule.PROJECT_VERSION_DESCRIPTION.optional(rawDescription);
        lockProject(project);
        requireAvailableName(project, name);
        versions.insert(project.value(), UUID.randomUUID().toString(), name, description);
    }

    public void update(String rawProject, String rawOriginalName, String rawName, String rawDescription) {
        ProjectId project = ProjectId.of(rawProject);
        String originalName = TextRule.PROJECT_VERSION_NAME.required(rawOriginalName);
        String name = TextRule.PROJECT_VERSION_NAME.required(rawName);
        String description = TextRule.PROJECT_VERSION_DESCRIPTION.optional(rawDescription);
        lockProject(project);
        String versionId = requireVersion(project, originalName).id();
        if (!Objects.equals(name, originalName)) {
            requireAvailableName(project, name);
        }
        versions.update(project.value(), versionId, name, description);
    }

    public void changeIssueLink(String rawIssue, String rawName, boolean attach) {
        IssueId id = IssueId.of(rawIssue);
        var issue = issues.findById(id)
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("issue", id.value())));
        ProjectId project = issue.projectId();
        String name = TextRule.PROJECT_VERSION_NAME.required(rawName);
        lockProject(project);
        String versionId = requireVersion(project, name).id();
        if (attach) {
            versions.link(project.value(), id.value(), versionId);
        } else {
            versions.unlink(project.value(), id.value(), versionId);
        }
    }

    /** The author batch pins and authorizes this project before invoking the shared version mutation. */
    public Outcome applyAuthorizedChange(Change change) {
        ProjectId project = ProjectId.of(change.projectId());
        String name = TextRule.PROJECT_VERSION_NAME.required(change.name());
        String description = TextRule.PROJECT_VERSION_DESCRIPTION.optional(change.description());
        var linked = issueIds(change.linkIssueIds(), "link_issue_ids");
        var unlinked = issueIds(change.unlinkIssueIds(), "unlink_issue_ids");
        validateIssueSelection(project, linked, unlinked);
        String versionId;
        if (change.create()) {
            if (change.originalName() != null) {
                throw ValidationException.invalid("original_name", ValidationReason.EXISTS_ONLY_FOR, "edit");
            }
            requireAvailableName(project, name);
            versionId = UUID.randomUUID().toString();
            versions.insert(project.value(), versionId, name, description);
        } else {
            var current = requireVersion(project, change.originalName() == null ? name
                    : TextRule.PROJECT_VERSION_NAME.required(change.originalName()));
            versionId = current.id();
            if (!Objects.equals(current.name(), name)) {
                requireAvailableName(project, name);
            }
            if (!Objects.equals(current.name(), name) || change.description() != null) {
                versions.update(project.value(), versionId, name,
                        change.description() == null ? current.description() : description);
            }
        }
        return new Outcome(versionId, name, change.create(),
                linked.stream().filter(id -> versions.link(project.value(), id, versionId)).toList(),
                unlinked.stream().filter(id -> versions.unlink(project.value(), id, versionId)).toList());
    }

    private void validateIssueSelection(ProjectId project, Set<String> linked, Set<String> unlinked) {
        if (linked.stream().anyMatch(unlinked::contains)) {
            throw ValidationException.invalid("unlink_issue_ids", ValidationReason.MUST_DIFFER_FROM, "link_issue_ids");
        }
        Set<String> selected = new LinkedHashSet<>(linked);
        selected.addAll(unlinked);
        Set<String> sameProject = lookup.findAny(selected).stream()
                .filter(row -> row.entity() == HistoryEntity.ISSUE && Objects.equals(project.value(), row.row().projectId()))
                .map(row -> row.row().id()).collect(Collectors.toSet());
        selected.stream().filter(id -> !sameProject.contains(id)).findFirst().ifPresent(id -> {
            throw new LookupException(LookupCode.NOT_FOUND, List.of("issue", id));
        });
    }

    private static Set<String> issueIds(List<String> ids, String path) {
        Set<String> unique = new LinkedHashSet<>();
        if (ids == null) {
            return unique;
        }
        if (ids.size() > MAX_ISSUE_LINKS) {
            throw ValidationException.invalid(path, ValidationReason.MUST_HOLD_ITEMS, "0", Integer.toString(MAX_ISSUE_LINKS));
        }
        for (String id : ids) {
            unique.add(IssueId.of(id).value());
        }
        return unique;
    }

    private void lockProject(ProjectId id) {
        // Serialize edits and issue links in the same project without database uniqueness constraints.
        var project = projects.findById(id).filter(row -> Objects.equals(row.id(), id))
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("project", id.value())));
        if (!projects.pinVersion(id, project.version())) {
            throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION, List.of("project", id.value()));
        }
    }

    private void requireAvailableName(ProjectId project, String name) {
        if (versions.find(project.value(), name).isPresent()) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of(name));
        }
    }

    private ProjectVersionRepository.Stored requireVersion(ProjectId project, String name) {
        return versions.find(project.value(), name)
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("project_version", name)));
    }

}
