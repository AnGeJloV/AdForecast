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
@Table(name = "forecast_results")
public class ForecastResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "forecast_id", unique = true)
    private Forecast forecast;

    @Column(name = "payback_months")
    private BigDecimal paybackMonths;

    @Column(name = "forecast_roi")
    private BigDecimal forecastRoi;

    @Column(name = "payback_probability")
    private BigDecimal paybackProbability;

    @Column(nullable = false)
    private BigDecimal mape;
}
