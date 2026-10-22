package com.ohcp.cropagent.api;

import com.ohcp.cropagent.knowledge.EvidenceSearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Actual backend contract consumed by java-ohcp/Agent's HttpKnowledgeSearch. */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeSearchController {
    public record SearchQuery(@NotBlank @Size(max = 100) String crop,
                              @NotBlank @Size(max = 16000) String query, @Min(1) @Max(20) int topK) {}
    public record SearchResponse(List<EvidenceSearchService.Match> matches) {}
    private final EvidenceSearchService search;
    public KnowledgeSearchController(EvidenceSearchService search) { this.search = search; }

    @PostMapping("/search")
    public ApiResponse<SearchResponse> search(@Valid @RequestBody SearchQuery query) {
        return ApiResponse.ok(new SearchResponse(search.search(query.crop(), query.query(), query.topK())));
    }
}
