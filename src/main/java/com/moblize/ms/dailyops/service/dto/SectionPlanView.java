
package com.moblize.ms.dailyops.service.dto;

import javax.annotation.Generated;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "targetWindowsData",
    "footagePercentage"
})
@Setter @Getter @NoArgsConstructor
public class SectionPlanView {

    @JsonProperty("targetWindowsData")
    public TargetWindowsData targetWindowsData;
    @JsonProperty("footagePercentage")
    public Float footagePercentage;

}
