package com.ohcp.cropagent.api;

import com.ohcp.cropagent.agent.AdviceComposer;
import com.ohcp.cropagent.agent.DiagnosisService;
import com.ohcp.cropagent.api.dto.Dtos.Advice;
import com.ohcp.cropagent.api.dto.Dtos.AdviceQuery;
import com.ohcp.cropagent.api.dto.Dtos.DiagnoseResult;
import com.ohcp.cropagent.api.dto.Dtos.SymptomQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DiagnoseController {

    private final DiagnosisService service;
    private final AdviceComposer composer;

    public DiagnoseController(DiagnosisService service, AdviceComposer composer) {
        this.service = service;
        this.composer = composer;
    }

    @PostMapping("/diagnose")
    public ApiResponse<DiagnoseResult> diagnose(@Valid @RequestBody SymptomQuery query) {
        long t0 = System.currentTimeMillis();
        ApiResponse<DiagnoseResult> resp = ApiResponse.ok(service.diagnose(query));
        resp.setCostMs(System.currentTimeMillis() - t0);
        return resp;
    }

    /** 局部刷新：只取某个元素的方案，不重跑识别。 */
    @PostMapping("/advice")
    public ApiResponse<Advice> advice(@Valid @RequestBody AdviceQuery query) {
        long t0 = System.currentTimeMillis();
        ApiResponse<Advice> resp = ApiResponse.ok(composer.compose(query.element(), query.crop(), query.mode()));
        resp.setCostMs(System.currentTimeMillis() - t0);
        return resp;
    }
}
