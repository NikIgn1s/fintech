package dev.finflow.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

final class ModuleBoundaryCondition extends ArchCondition<JavaClass> {
    private final String root;

    ModuleBoundaryCondition(String root) {
        super("access other modules only through their api packages");
        this.root=root;
    }

    @Override
    public void check(JavaClass source,ConditionEvents events) {
        String sourceModule=module(source.getPackageName());
        if (sourceModule.isEmpty()) return;
        source.getDirectDependenciesFromSelf().forEach(dependency -> {
            JavaClass target=dependency.getTargetClass();
            String targetModule=module(target.getPackageName());
            if (targetModule.isEmpty() || sourceModule.equals(targetModule) || targetModule.equals("common")) return;
            String exported=root+targetModule+".api";
            boolean internal=!target.getPackageName().equals(exported)
                    && !target.getPackageName().startsWith(exported+".");
            if (internal || target.isAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)) {
                events.add(SimpleConditionEvent.violated(dependency,dependency.getDescription()));
            }
        });
    }

    private String module(String name) {
        if (!name.startsWith(root)) return "";
        String relative=name.substring(root.length());
        int dot=relative.indexOf('.');
        return dot<0?relative:relative.substring(0,dot);
    }
}
