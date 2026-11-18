package com.ohcp.cropagent.ontology;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.reasoner.rulesys.RuleDerivation;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Executes file-backed rules against an isolated observation. Never persists user facts. */
@Service
public class OntologyReasoningService {
    private static final String NS = "https://ohcp.example/ontology#";
    private static final String CASE = "urn:ohcp:observation";
    private final Path root;

    public OntologyReasoningService(@Value("${agent.ontology.dir:../data/ontology}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    public record ObservedSymptom(@NotBlank @Size(max = 80) String part,
                                  @NotBlank @Size(max = 80) String pattern, boolean first) {}
    public record Request(@Size(max = 80) String crop,
                          @NotEmpty @Size(max = 10) List<@NotNull @Valid ObservedSymptom> observations) {}
    public record Evidence(String subject, String predicate, String object, boolean asserted) {}
    public record Step(String ruleId, String ruleText, String reason,
                       Evidence conclusion, List<Evidence> premises) {}
    public record Candidate(String id, String label, String nutrient, String decision, boolean prioritized,
                            String reason, List<Step> steps) {}
    public record Conflict(String subject, String type, String reason, List<Step> steps) {}
    public record Classification(String observation, List<String> types, List<Step> steps) {}
    public record QueryTrace(String sparql, List<Map<String, String>> rows) {}
    public record Result(String status, String scope, String message, List<Candidate> candidates,
                         List<Conflict> conflicts, List<Classification> classifications,
                         QueryTrace query, List<Step> steps) {}

    public Result analyze(Request request) throws IOException {
        validate(request);
        Model base = RDFDataMgr.loadModel(root.resolve("ohcp-ontology.ttl").toString());
        InfModel inferred = null;
        try {
            RDFDataMgr.read(base, root.resolve("reasoning-schema.ttl").toString());
            String source = Files.readString(root.resolve("ohcp.rules"));
            List<Rule> rules = Rule.parseRules(source);
            Map<String, String> ruleSources = ruleSources(source, rules);
            GenericRuleReasoner reasoner = new GenericRuleReasoner(rules);
            reasoner.setMode(GenericRuleReasoner.FORWARD_RETE);
            reasoner.setDerivationLogging(true);
            Resource observation = base.createResource(CASE);
            List<Resource> observations = new ArrayList<>();
            for (ObservedSymptom item : request.observations()) {
                if (!isType(base, item.part(), "PlantPart") || !isType(base, item.pattern(), "Pattern")) {
                    return uncovered("知识库未收录该观察部位或形态：" + item.part() + " / " + item.pattern());
                }
            }
            if (request.crop() != null && !request.crop().isBlank() && !isType(base, request.crop(), "Crop")) {
                return uncovered("知识库未收录该作物：" + request.crop());
            }
            for (ObservedSymptom item : request.observations()) {
                Resource symptom = base.createResource(CASE + ":symptom:" + observations.size());
                base.add(observation, RDF.type, base.createResource(NS + "Observation"));
                base.add(observation, property(base, "observedSymptom"), symptom);
                base.add(observation, property(base, "observedPattern"), base.createResource(NS + item.pattern()));
                base.add(symptom, property(base, "symptomLocatedIn"), base.createResource(NS + item.part()));
                base.add(symptom, property(base, "hasPattern"), base.createResource(NS + item.pattern()));
                if (item.first()) base.add(observation, property(base, "firstAffectedPart"), base.createResource(NS + item.part()));
                observations.add(symptom);
            }
            QueryTrace query = candidatesQuery(base);
            inferred = ModelFactory.createInfModel(reasoner, base);
            inferred.prepare();
            List<Step> steps = collectSteps(base, inferred, ruleSources);
            List<Conflict> conflicts = new ArrayList<>();
            StmtIterator conflictStatements = inferred.listStatements(null, property(base, "conflict"), (RDFNode) null);
            try {
                while (conflictStatements.hasNext()) {
                    Statement statement = conflictStatements.next();
                    String type = statement.getResource().getLocalName();
                    conflicts.add(new Conflict(statement.getSubject().getURI(), type,
                            explanation(base, type, "本体事实存在矛盾，需先修正"), forConclusion(steps, statement)));
                }
            } finally { conflictStatements.close(); }
            conflicts.sort(Comparator.comparing(Conflict::subject).thenComparing(Conflict::type));
            List<Classification> classifications = new ArrayList<>();
            for (Resource symptom : observations) {
                List<String> types = inferred.listObjectsOfProperty(symptom, RDF.type).toList().stream()
                        .filter(RDFNode::isURIResource).map(node -> node.asResource().getURI()).sorted().toList();
                classifications.add(new Classification(symptom.getURI(), types, steps.stream()
                        .filter(step -> step.conclusion().subject().equals(symptom.getURI())
                                && step.conclusion().predicate().equals(RDF.type.getURI())).toList()));
            }
            List<Candidate> candidates = new ArrayList<>();
            for (Map<String, String> row : query.rows()) {
                Resource disorder = base.createResource(row.get("disorder"));
                boolean supported = inferred.contains(observation, property(base, "candidate"), disorder);
                boolean excluded = inferred.contains(observation, property(base, "excluded"), disorder);
                String decision = !conflicts.isEmpty() ? "WITHHELD" : excluded ? "EXCLUDED" : supported ? "SUPPORTED" : "UNSUPPORTED";
                List<Step> related = steps.stream().filter(step -> step.conclusion().subject().equals(CASE)
                        && step.conclusion().object().equals(disorder.getURI())).toList();
                String reason = switch (decision) {
                    case "WITHHELD" -> "检测到冲突，停止输出诊断候选，请先核对观察事实或本体分类。";
                    case "EXCLUDED" -> decisionReason(related, "excluded");
                    case "SUPPORTED" -> decisionReason(related, "candidate");
                    default -> "本次观察没有触发支持该候选的规则；不等同于排除。";
                };
                boolean prioritized = decision.equals("SUPPORTED") && inferred.contains(observation, property(base, "prioritized"), disorder);
                candidates.add(new Candidate(disorder.getLocalName(), row.get("label"), row.get("nutrient"), decision, prioritized, reason, related));
            }
            candidates.sort(Comparator.comparing(Candidate::prioritized).reversed().thenComparing(Candidate::id));
            String status = !conflicts.isEmpty() ? "CONFLICT" : candidates.stream().anyMatch(c -> c.decision().equals("SUPPORTED")) ? "CANDIDATES" : "NO_MATCH";
            return new Result(status, "GENERIC_ONLY",
                    "仅通用症状推理；作物适用关系尚未审核，不提供确诊、概率或施肥用量。", candidates,
                    conflicts, classifications, query, steps);
        } finally {
            if (inferred != null) inferred.close();
            base.close();
        }
    }

    private static void validate(Request request) {
        if (request == null || request.observations() == null || request.observations().isEmpty()
                || request.observations().size() > 10) throw new IllegalArgumentException("请提供 1–10 条观察");
        if (request.crop() != null && !request.crop().isBlank()) checkId(request.crop());
        for (ObservedSymptom item : request.observations()) {
            if (item == null) throw new IllegalArgumentException("观察不能为空");
            checkId(item.part()); checkId(item.pattern());
        }
    }

    private static void checkId(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9]{0,79}")) throw new IllegalArgumentException("实体标识格式不合法");
    }

    private static boolean isType(Model model, String id, String type) {
        Resource entity = model.createResource(NS + id), target = model.createResource(NS + type);
        Set<Resource> visited = new HashSet<>();
        Deque<Resource> pending = new ArrayDeque<>();
        model.listObjectsOfProperty(entity, RDF.type).forEachRemaining(node -> { if (node.isResource()) pending.add(node.asResource()); });
        while (!pending.isEmpty()) {
            Resource current = pending.removeFirst();
            if (current.equals(target)) return true;
            if (visited.add(current)) model.listObjectsOfProperty(current, RDFS.subClassOf)
                    .forEachRemaining(node -> { if (node.isResource()) pending.add(node.asResource()); });
        }
        return false;
    }

    private QueryTrace candidatesQuery(Model base) throws IOException {
        String sparql = Files.readString(root.resolve("queries/reasoning-candidates.rq"));
        List<Map<String, String>> rows = new ArrayList<>();
        try (QueryExecution execution = QueryExecutionFactory.create(sparql, base)) {
            ResultSet result = execution.execSelect();
            while (result.hasNext()) {
                QuerySolution solution = result.next();
                rows.add(Map.of("disorder", solution.getResource("disorder").getURI(),
                        "label", solution.getLiteral("label").getString(), "nutrient", solution.getResource("nutrient").getURI()));
            }
        }
        return new QueryTrace(sparql, List.copyOf(rows));
    }

    private static List<Step> collectSteps(Model base, InfModel inferred, Map<String, String> sources) {
        List<Step> steps = new ArrayList<>();
        StmtIterator statements = inferred.getDeductionsModel().listStatements();
        try {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                Iterator<org.apache.jena.reasoner.Derivation> derivations = inferred.getDerivation(statement);
                if (derivations == null) continue;
                while (derivations.hasNext()) {
                    if (derivations.next() instanceof RuleDerivation derivation) {
                        String id = derivation.getRule().getName();
                        List<Evidence> premises = derivation.getMatches().stream().filter(Objects::nonNull)
                                .map(triple -> evidence(base, triple)).toList();
                        steps.add(new Step(id, sources.get(id), explanation(base, id, id), evidence(base, derivation.getConclusion()), premises));
                    }
                }
            }
        } finally { statements.close(); }
        return steps.stream().distinct().sorted(Comparator.comparing(Step::ruleId)
                .thenComparing(step -> step.conclusion().toString())).toList();
    }

    private static Evidence evidence(Model base, Triple triple) {
        return new Evidence(triple.getSubject().getURI(), triple.getPredicate().getURI(),
                triple.getObject().isURI() ? triple.getObject().getURI() : triple.getObject().toString(), base.getGraph().contains(triple));
    }

    private static List<Step> forConclusion(List<Step> steps, Statement statement) {
        return steps.stream().filter(step -> step.conclusion().subject().equals(statement.getSubject().getURI())
                && step.conclusion().predicate().equals(statement.getPredicate().getURI())
                && step.conclusion().object().equals(statement.getResource().getURI())).toList();
    }

    private static Property property(Model model, String local) { return model.createProperty(NS + local); }
    private static String decisionReason(List<Step> related, String predicate) {
        return related.stream().filter(step -> step.conclusion().predicate().equals(NS + predicate))
                .map(Step::reason).distinct().collect(java.util.stream.Collectors.joining("；"));
    }
    private static String explanation(Model model, String local, String fallback) {
        RDFNode value = model.getProperty(model.createResource(NS + local), property(model, "explanation")) == null ? null
                : model.getProperty(model.createResource(NS + local), property(model, "explanation")).getObject();
        return value != null && value.isLiteral() ? value.asLiteral().getString() : fallback;
    }

    private static Map<String, String> ruleSources(String source, List<Rule> rules) {
        Map<String, String> sources = new HashMap<>();
        var matcher = Pattern.compile("\\[([A-Za-z][A-Za-z0-9]*):[^\\[\\]]*]", Pattern.DOTALL).matcher(source);
        while (matcher.find()) sources.put(matcher.group(1), matcher.group());
        if (rules.stream().anyMatch(rule -> !sources.containsKey(rule.getName())) || sources.size() != rules.size())
            throw new IllegalStateException("规则必须具有唯一名称且不嵌套，以保留可核对的原文");
        return sources;
    }

    private static Result uncovered(String message) {
        return new Result("NOT_COVERED", "GENERIC_ONLY", message, List.of(), List.of(), List.of(), null, List.of());
    }
}
