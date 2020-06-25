@Grapes([
    @Grab(group='org.semanticweb.elk', module='elk-owlapi', version='0.4.3'),
    @Grab(group='net.sourceforge.owlapi', module='owlapi-api', version='5.1.14'),
    @Grab(group='net.sourceforge.owlapi', module='owlapi-apibinding', version='5.1.14'),
    @Grab(group='net.sourceforge.owlapi', module='owlapi-impl', version='5.1.14'),
    @Grab(group='net.sourceforge.owlapi', module='owlapi-parsers', version='5.1.14'),
    @Grab(group='net.sourceforge.owlapi', module='owlapi-distribution', version='5.1.14'),
    @GrabConfig(systemClassLoader=true)
])

import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.model.parameters.*
import org.semanticweb.elk.owlapi.ElkReasonerFactory
import org.semanticweb.elk.owlapi.ElkReasonerConfiguration
import org.semanticweb.elk.reasoner.config.*
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.reasoner.*
import org.semanticweb.owlapi.reasoner.structural.StructuralReasoner
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary
import org.semanticweb.owlapi.model.*
import org.semanticweb.owlapi.io.*
import org.semanticweb.owlapi.owllink.*
import org.semanticweb.owlapi.util.*
import org.semanticweb.owlapi.search.*
import org.semanticweb.owlapi.manchestersyntax.renderer.*
import org.semanticweb.owlapi.reasoner.structural.*
import java.util.regex.Pattern
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import groovyx.gpars.*
import org.codehaus.gpars.*

def ukb = [:]
def openUkb = { f ->
  new File(f).splitEachLine('\t') { 
    ukb[it[0]] = [ id: it[0], label: it[1].toLowerCase(), category: it[2].toLowerCase().trim() ]
  }
}
openUkb('./ukb_traits/one_traits.tsv')
openUkb('./ukb_traits/two_traits.tsv')

println "UKB Traits: ${ukb.size()}"

def mapping = [:]
new File('ann/anns.tsv').splitEachLine('\t') {
  println it[it.size()-2]
  mapping[it[it.size()-2]] = it[1]
}

println "mapping size: (${mapping.size()}/${ukb.size()})"

// TODO load the annotations file, and set mappings based on that.

def manager = OWLManager.createOWLOntologyManager()
def factory = manager.getOWLDataFactory()

def ont = manager.createOntology(IRI.create("http://reality.rehab/ecmo.owl"))
def cmo = manager.loadOntologyFromOntologyDocument(new File("inputs/cmo.owl"))

manager.applyChange(new AddImport(ont, 
  factory.getOWLImportsDeclaration(IRI.create("http://purl.obolibrary.org/obo/cmo.owl"))))

def cMeasureClass = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000000"))

def ecmoPrefix = "http://reality.rehab/ECMO_"
def ecmoCounter = 0

def addClass = { iri, label, scOf -> 
  def cClass = factory.getOWLClass(IRI.create(iri))

  println "adding class..."

  manager.addAxiom(ont, factory.getOWLSubClassOfAxiom(cClass, scOf))

  println "adding $label with $iri"

  def anno = factory.getOWLAnnotation(
       factory.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI()),
       factory.getOWLLiteral(label))
  def axiom =  factory.getOWLAnnotationAssertionAxiom(cClass.getIRI(), anno)
  manager.addAxiom(ont, axiom)

  cClass
}

// Add the age stuff 
def ageParentClass = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0001108"))
ukb.findAll { id, t -> t = t.label ; t =~ 'age' && t =~ 'diag' }
   .collectEntries { id, t ->
      if(t.label.indexOf(' by doctor') != -1) {
        t.label = t.label.substring(0, t.label.indexOf(' by doctor')) 
      }
      t.label = t.label.replace('age ', '')
      t.label = t.label.replace('at ', '')
      t.label = t.label.replace('diagnosed', '')
      t.label = t.label.replace('diagnosis', '')
      t.label = t.label.trim()

      [(id): t]
    }
    .each { id, t ->
      def firstLabel = "$t.label onset/diagnosis measurement"
      def secondLabel = "age at onset/diagnosis of $t.label"
      def firstIri = ecmoPrefix + (++ecmoCounter)
      def secondIri = ecmoPrefix + (++ecmoCounter)

      def ageMeas = addClass(firstIri, firstLabel, ageParentClass)
      addClass(secondIri, secondLabel, ageMeas)

      mapping[id] = secondIri
    }

println "mapping size: (${mapping.size()}/${ukb.size()})"

// Add the alcohol stuff
def alcoholParentClass = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0001407"))
ukb.findAll { id, t -> t.category == 'alcohol' }
   .each { id, t ->
     def newIri = ecmoPrefix + (++ecmoCounter)
     addClass(newIri, t.label, alcoholParentClass) 
     mapping[id] = newIri
   }

println "mapping size: (${mapping.size()}/${ukb.size()})"

def refracMeasure = addClass(ecmoPrefix, "ocular autorefraction measurement", cMeasureClass)
ukb.findAll { id, t -> t.category == 'autorefraction' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, refracMeasure) 
    mapping[id] = newIri
  }

println "mapping size: (${mapping.size()}/${ukb.size()})"

// Yeah, it's not actually abdominal composition
def abdominalComposition = ukb.findAll


def groups = [:]
ukb.each { id, t ->
  if(!mapping[id]) {
    if(!groups[t.category]) {
      groups[t.category] = 0
    }
    groups[t.category]++
  }
}

println groups

// we need to add drink intake frequency measurement 0000771

// Iterate ontology classes

// add volume

// for all x bmc add child of cmo:0001554
// for all uberon fats?
// for all Age diagnosis son of 0001108 disease onset/diagnosis measurement

// fat areas:
// arm
// arms
// body
// leg
// trunk
// android
// arms
//total
// gynoid
// abdominal

// smoking measurement (lifestyle measurement?)


// add age measurement


// Iterate UKB items


manager.saveOntology(ont, IRI.create(new File('ecmo.owl')))
