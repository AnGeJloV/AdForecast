package by.bsuir.adforecast.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "audit_actions")
public class AuditAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String action;

    @Column(name = "object_type")
    private String objectType;

    @Column(name = "object_id")
    private Long objectId;

    @Column
    private String details;

    @Column(name = "action_time", nullable = false)
    private LocalDateTime actionTime;

    @Column
    private String ip;
}
