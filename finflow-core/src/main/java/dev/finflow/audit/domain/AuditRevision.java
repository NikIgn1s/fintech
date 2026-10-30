package dev.finflow.audit.domain;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.*;

@Entity
@Table(name="revinfo")
@RevisionEntity(AuditRevisionListener.class)
@Getter
@Setter
public class AuditRevision {
    @Id
    @GeneratedValue(strategy=GenerationType.SEQUENCE,generator="revision")
    @SequenceGenerator(name="revision",sequenceName="revision_seq",allocationSize=1)
    @RevisionNumber
    @Column(name="rev")
    private long id;

    @RevisionTimestamp
    @Column(name="revtstmp",nullable=false)
    private long timestamp;

    @Column(name="user_id")
    private UUID userId;

    @Column(name="ip",length=64)
    private String ip;
}
