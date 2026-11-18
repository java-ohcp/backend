"""Development-only ontology structure check; not a reasoner or human approval."""
from pathlib import Path
from rdflib import Graph, Namespace, RDF, RDFS, OWL

ns = Namespace("https://ohcp.example/ontology#")
g = Graph().parse(Path(__file__).with_name("ohcp-ontology.ttl"), format="turtle")
classes = set(g.subjects(RDF.type, OWL.Class))
individuals = set(g.subjects(RDF.type, OWL.NamedIndividual))
properties = set(g.subjects(RDF.type, OWL.ObjectProperty))
assert len(individuals) >= 15 and len(g) >= 30
for name in ("Crop", "PlantPart", "Symptom", "Nutrient", "DeficiencyDisorder"):
    assert ns[name] in classes
for prop in properties:
    assert len(list(g.objects(prop, RDFS.domain))) == 1
    assert len(list(g.objects(prop, RDFS.range))) == 1
    assert g.value(prop, RDFS.domain) in classes
    assert g.value(prop, RDFS.range) in classes
assert len(properties) >= 6
assert (ns.MacroNutrient, OWL.disjointWith, ns.MicroNutrient) in g
for node in classes | individuals | properties:
    assert g.value(node, RDFS.label) is not None
for node, parent in g.subject_objects(RDFS.subClassOf):
    assert node in classes and parent in classes
for individual in individuals:
    types = set(g.objects(individual, RDF.type)) - {OWL.NamedIndividual}
    assert types and types <= classes
for minimum, cls in ((3, ns.Crop), (4, ns.DeficiencyDisorder)):
    assert len(set(g.subjects(RDF.type, cls))) >= minimum
assert any((parent, RDFS.subClassOf, grandparent) in g
           for _, parent in g.subject_objects(RDFS.subClassOf)
           for grandparent in classes)
print(f"PASS: {len(g)} triples, {len(classes)} classes, {len(individuals)} individuals, {len(properties)} object properties")
