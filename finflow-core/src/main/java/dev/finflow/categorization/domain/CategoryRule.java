package dev.finflow.categorization.domain;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.envers.Audited;

@Entity
@Table(name="category_rules")
@Audited
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class CategoryRule {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;
    @Column(name="user_id",nullable=false)
    private UUID userId;
    @Column(nullable=false,length=255)
    private String pattern;
    @Column(name="category_id",nullable=false)
    private short categoryId;
    @Column(nullable=false)
    private int priority;

    public static CategoryRule create(UUID user,String pattern,short category,int priority) {
        CategoryRule entity=new CategoryRule();
        entity.userId=user;
        entity.pattern=pattern;
        entity.categoryId=category;
        entity.priority=priority;
        return entity;
    }
}
