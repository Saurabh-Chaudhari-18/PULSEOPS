package com.pulseops.project.domain;

/** The user-editable attributes of a project. */
public record ProjectDetails(String name, String description, String techStack, Environment environment,
                             String region, String applicationType) {

    /** Values from {@code patch} where non-null, otherwise from this. Blank strings clear optional fields. */
    public ProjectDetails merge(ProjectDetails patch) {
        return new ProjectDetails(
                patch.name() != null ? patch.name().trim() : name,
                pick(patch.description(), description),
                pick(patch.techStack(), techStack),
                patch.environment() != null ? patch.environment() : environment,
                pick(patch.region(), region),
                pick(patch.applicationType(), applicationType));
    }

    public ProjectDetails normalized() {
        return new ProjectDetails(name == null ? null : name.trim(), blankToNull(description), blankToNull(techStack),
                environment == null ? Environment.PRODUCTION : environment, blankToNull(region),
                blankToNull(applicationType));
    }

    private static String pick(String patched, String current) {
        if (patched == null) {
            return current;
        }
        return blankToNull(patched);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
