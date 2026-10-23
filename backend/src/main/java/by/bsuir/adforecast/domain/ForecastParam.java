package by.bsuir.adforecast.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "forecast_params")
public class ForecastParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "param_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "forecast_id", unique = true)
    private Forecast forecast;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "start_month")
    private Integer startMonth;

    @Column(name = "hist_days", nullable = false)
    private Integer histDays;

    @Column(name = "hist_cost")
    private BigDecimal histCost;

    @Column(name = "hist_revenue")
    private BigDecimal histRevenue;

    @Column(name = "confidence_level", nullable = false)
    private BigDecimal confidenceLevel;

    @Column(name = "use_seasonality", nullable = false)
    private Boolean useSeasonality;
}
