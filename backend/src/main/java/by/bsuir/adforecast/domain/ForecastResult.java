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

    @Column(name = "verdict")
    private String verdict;

    @Column(name = "payback_ratio")
    private BigDecimal paybackRatio;

    @Column(name = "forecast_roi")
    private BigDecimal forecastRoi;

    @Column(name = "interval_lower")
    private BigDecimal intervalLower;

    @Column(name = "interval_upper")
    private BigDecimal intervalUpper;

    @Column(name = "agreement")
    private BigDecimal agreement;

    @Column(name = "payback_months")
    private BigDecimal paybackMonths;

    @Column(name = "payback_days")
    private BigDecimal paybackDays;

    @Column(name = "expected_profit")
    private BigDecimal expectedProfit;

    @Column(name = "budget_min")
    private BigDecimal budgetMin;

    @Column(name = "budget_max")
    private BigDecimal budgetMax;

    @Column(name = "budget_any", nullable = false)
    private Boolean budgetAny;

    @Column(name = "model_scope", nullable = false)
    private String modelScope;

    @Column(name = "day_budget_min")
    private BigDecimal dayBudgetMin;

    @Column(name = "day_budget_max")
    private BigDecimal dayBudgetMax;

    @Column(name = "data_note")
    private String dataNote;

    @Column(name = "own_weight")
    private BigDecimal ownWeight;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "own_model_id")
    private MlModel ownModel;

    @Column(nullable = false)
    private BigDecimal mape;
}
