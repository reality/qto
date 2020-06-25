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
def addedLabels = [:]
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

def thing = factory.getOWLClass(IRI.create("http://www.w3.org/2002/07/owl#Thing"))
def cMeasureClass = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000000"))

def ecmoPrefix = "http://reality.rehab/ECMO_"
def ecmoCounter = 0

def addClass = { iri, label, scOf -> 
  def cClass
  if(!addedLabels[label]) {
    cClass = factory.getOWLClass(IRI.create(iri))
    println "adding class..."

    manager.addAxiom(ont, factory.getOWLSubClassOfAxiom(cClass, scOf))

    println "adding $label with $iri"

    def anno = factory.getOWLAnnotation(
         factory.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI()),
         factory.getOWLLiteral(label))
    def axiom =  factory.getOWLAnnotationAssertionAxiom(cClass.getIRI(), anno)
    manager.addAxiom(ont, axiom)

    addedLabels[label] = iri
  } else {
    println 'LINKING TO ALREADY CREATED '
    cClass = factory.getOWLClass(IRI.create(addedLabels[label]))
  }

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
ukb.findAll { id, t -> t.category == 'alcohol' || t.category == 'alcohol use' }
   .each { id, t ->
     def newIri = ecmoPrefix + (++ecmoCounter)
     addClass(newIri, t.label, alcoholParentClass) 
     mapping[id] = newIri
   }

println "mapping size: (${mapping.size()}/${ukb.size()})"

def refracMeasure = addClass(ecmoPrefix + (++ecmoCounter), "ocular autorefraction measurement", cMeasureClass)
ukb.findAll { id, t -> t.category == 'autorefraction' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, refracMeasure) 
    mapping[id] = newIri
  }

println "mapping size: (${mapping.size()}/${ukb.size()})"

def leanTissueMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0002184"))
def leanTissueVolume = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0002185"))
def muscleMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000131"))
def adiposeMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000484"))
def abFatMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000311"))

def bodyRegionFat = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000224"))

def fatMorph = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000089"))
def impedanceMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000126"))

// add muscle voluem sub of 
def muscleVol = addClass(ecmoPrefix + (++ecmoCounter), "muscle volume", muscleMeas)

def trunkFat = addClass(ecmoPrefix + (++ecmoCounter), "trunk fat morphological measurement", bodyRegionFat)
def armFat = addClass(ecmoPrefix + (++ecmoCounter), "arm fat morphological measurement", bodyRegionFat)
def gynoidFat = addClass(ecmoPrefix + (++ecmoCounter), "gynoid fat morphological measurement", bodyRegionFat)
def legFat = addClass(ecmoPrefix + (++ecmoCounter), "leg fat morphological measurement", bodyRegionFat)
def androidFat = addClass(ecmoPrefix + (++ecmoCounter), "android fat morphological measurement", bodyRegionFat)

def totalFatMass = addClass(ecmoPrefix + (++ecmoCounter), "total body fat mass measurement", fatMorph)

// Yeah, it's not actually abdominal composition
def abdominalComposition = ukb.findAll { id, t -> 
    t.category == 'abdominal composition' || t.category == 'body composition by dxa' || t.category == 'impedance measures'
  }
  .each { id, t ->
    if(mapping[id]) { return; }
    t.label = t.label.replace('whole body', 'total')

    if(id == '22416') { 
      mapping['22416'] = "http://purl.obolibrary.org/obo/CMO_0002186" 
    } else if(id == '23104') {
      mapping['23104'] = "http://purl.obolibrary.org/obo/CMO_0000105" 
    } else if(t.label =~ 'muscle') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      if(t.label =~ 'lean muscle volume')  {
        addClass(newIri, t.label, muscleVol)
      } else {
        addClass(newIri, t.label, muscleMeas)
      }
      mapping[id] = newIri
    } else if(t.label =~ 'adipose') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, adiposeMeas)
      mapping[id] = newIri
    } else if(t.label =~ 'abdominal fat'){
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, abFatMeas)
      mapping[id] = newIri
    } else if(t.label =~ '^trunk') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, trunkFat)
      mapping[id] = newIri
    } else if(t.label =~ '^leg') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, legFat)
      mapping[id] = newIri
    } else if(t.label =~ '^arm') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, armFat)
      mapping[id] = newIri
    } else if(t.label =~ '^android') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, androidFat)
      mapping[id] = newIri
    } else if(t.label =~ '^gynoid') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, armFat)
      mapping[id] = newIri
    } else if(t.label =~ 'total' && t.label =~ 'mass') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, totalFatMass)
      mapping[id] = newIri
    } else if(t.label =~ 'impedance') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, impedanceMeas)
      mapping[id] = newIri
    } else if(t.label =~ 'total tissue fat percentage') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, fatMorph)
      mapping[id] = newIri
    } else {
      println "UNMATCHED: $id:$t.label"
    }
  }

println "mapping size: (${mapping.size()}/${ukb.size()})"

def depMeasurement = addClass(ecmoPrefix + (++ecmoCounter), "multiple deprivation measurement", cMeasureClass)
def healthScore = addClass(ecmoPrefix + (++ecmoCounter), "health score measurement", depMeasurement)
def employScore = addClass(ecmoPrefix + (++ecmoCounter), "employment score measurement", depMeasurement)
def incomeScore = addClass(ecmoPrefix + (++ecmoCounter), "income score measurement", depMeasurement)
def crimeScore = addClass(ecmoPrefix + (++ecmoCounter), "crime score measurement", depMeasurement)
ukb.findAll { id, t -> t.category == 'indices of multiple deprivation' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)

    def pClass = depMeasurement
    if(t.label =~ 'health') {
      pClass = healthScore
    } else if(t.label =~ 'employ') {
      pClass = employScore
    } else if(t.label =~ 'income') {
      pClass = incomeScore
    } else if(t.label =~ 'crime') {
      pClass = crimeScore
    }

    addClass(newIri, t.label, pClass) 
    mapping[id] = newIri
  }

println "mapping size: (${mapping.size()}/${ukb.size()})"

def accellMeas = addClass(ecmoPrefix + (++ecmoCounter), "accelerometer calibration measurement", thing)
ukb.findAll { id, t -> t.category == 'accelerometer calibration' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, accellMeas) 
    mapping[id] = newIri
  }

println "mapping size: (${mapping.size()}/${ukb.size()})"

def reproMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000389"))
def femMeas = addClass(ecmoPrefix + (++ecmoCounter), "female-specific reproduction measurement", reproMeas)
mapping['2754'] = "http://purl.obolibrary.org/obo/CMO_0002510" 

ukb.findAll { id, t -> t.category == 'female-specific factors' }
  .each { id, t ->
    if(mapping[id]) { return; }
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, femMeas) 
    mapping[id] = newIri
  }

println "mapping size: (${mapping.size()}/${ukb.size()})"


def protMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000028"))
def bloodChem = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000023"))

mapping['30670'] = "http://purl.obolibrary.org/obo/CMO_0000049" 
mapping['30860'] = "http://purl.obolibrary.org/obo/CMO_0000054" 
mapping['30770'] = "http://purl.obolibrary.org/obo/CMO_0001297" 
mapping['30870'] = "http://purl.obolibrary.org/obo/CMO_0000118" 
mapping['30620'] = "http://purl.obolibrary.org/obo/CMO_0000574" 
mapping['30610'] = "http://purl.obolibrary.org/obo/CMO_0000576" 
mapping['30650'] = "http://purl.obolibrary.org/obo/CMO_0000580" 
mapping['30780'] = "http://purl.obolibrary.org/obo/CMO_0000647" 
mapping['30730'] = "http://purl.obolibrary.org/obo/CMO_0002239" 
mapping['30800'] = "http://purl.obolibrary.org/obo/CMO_0000513" 
mapping['30880'] = "http://purl.obolibrary.org/obo/CMO_0000501" 
ukb.findAll { id, t -> t.category == 'blood biochemistry' }
  .each { id, t ->
    if(mapping[id]) { return; }

    if(t.label =~ 'protein' || t.label =~ 'rheumatoid factor' || t.label =~ 'shbg') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, protMeas) 
      mapping[id] = newIri
    } else {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, bloodChem) 
      mapping[id] = newIri
    }
  }

def immMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0001864"))
def immPerc = addClass(ecmoPrefix + (++ecmoCounter), "immune cell percentage", immMeas)
immPerc = addClass(ecmoPrefix + (++ecmoCounter), "white blood cell percentage", immPerc)
def granImmPerc = addClass(ecmoPrefix + (++ecmoCounter), "blood granulocyte percentage", immPerc)
def monoImmPerc = addClass(ecmoPrefix + (++ecmoCounter), "blood granulocyte percentage", immPerc)
def redMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0001356"))
def hemo = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000037"))

mapping['30000'] = "http://purl.obolibrary.org/obo/CMO_0000027" 
ukb.findAll { id, t -> t.category == 'blood count' }
  .each { id, t ->
    if(mapping[id]) { return; }
    if(t.label =~ 'basophil' || t.label =~ 'eosinophil' || t.label =~ 'neutrophil') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, granImmPerc) 
      mapping[id] = newIri
    } else if(t.label =~ 'lymphocyte' || t.label =~ 'monocyte') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, monoImmPerc) 
      mapping[id] = newIri
    } else if(t.label =~ 'reticulocyte' || t.label =~ 'red') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, redMeas) 
      mapping[id] = newIri
    } else if(t.label =~ 'haematocrit') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, hemo) 
      mapping[id] = newIri
    } else {
      println t
    }
  }

def cogMeas = addClass(ecmoPrefix + (++ecmoCounter), "cognitive performance measurement", cMeasureClass)
def trailMeas = addClass(ecmoPrefix + (++ecmoCounter), "trail making measurement", cogMeas)
def pairsMeas = addClass(ecmoPrefix + (++ecmoCounter), "pair matching measurement", cogMeas)
ukb.findAll { id, t -> t.category == 'trail making' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, trailMeas) 
    mapping[id] = newIri
  }
ukb.findAll { id, t -> t.category == 'pairs matching' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, pairsMeas) 
    mapping[id] = newIri
  }
ukb.findAll { id, t -> t.category == 'reaction time' || t.category == 'tower rearranging' || t.category == 'fluid intelligence / reasoning' || t.category == 'symbol digit substitution' || t.category == 'matrix pattern completion' || t.category == 'numeric memory' || t.category == 'paired associate learning' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, cogMeas) 
    mapping[id] = newIri
  }

def empHistory = addClass(ecmoPrefix + (++ecmoCounter), "employment history measurement", cMeasureClass)
ukb.findAll { id, t -> t.category == 'employment history' || t.category == 'employment' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, empHistory) 
    mapping[id] = newIri
  }

def intakeMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000426"))
def smokMeas = addClass(ecmoPrefix + (++ecmoCounter), "smoking intake measurement", intakeMeas)
ukb.findAll { id, t -> t.category == 'smoking' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, smokMeas) 
    mapping[id] = newIri
  }
ukb.findAll { id, t -> t.category == 'cannabis use' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, intakeMeas) 
    mapping[id] = newIri
  }

def artMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000974"))
def carotidMeas = addClass(ecmoPrefix + (++ecmoCounter), "carotid artery measurement", artMeas)
ukb.findAll { id, t -> t.category == 'carotid ultrasound' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, carotidMeas) 
    mapping[id] = newIri
  }

def foodMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000772"))
ukb.findAll { id, t -> t.category == 'diet' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, foodMeas) 
    mapping[id] = newIri
  }

def physMeas = addClass(ecmoPrefix + (++ecmoCounter), "physical activity measurement", cMeasureClass)
ukb.findAll { id, t -> t.category == 'physical activity' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, physMeas) 
    mapping[id] = newIri
  }

def famMeas = addClass(ecmoPrefix + (++ecmoCounter), "family history measurement", cMeasureClass)
ukb.findAll { id, t -> t.category == 'family history' }
  .each { id, t ->
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, famMeas) 
    mapping[id] = newIri
  }

def orgMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000669"))
def ocularMeas = addClass(ecmoPrefix + (++ecmoCounter), "ocular organ measurement", orgMeas)
def cornealMeas = addClass(ecmoPrefix + (++ecmoCounter), "corneal measurement", ocularMeas)
def intraocularMeas = addClass(ecmoPrefix + (++ecmoCounter), "intra-ocular measurement", ocularMeas)
ukb.findAll { id, t -> t.category == 'intraocular pressure' }
  .each { id, t ->
    if(t.label =~ 'corneal') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, cornealMeas) 
      mapping[id] = newIri
    } else {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, intraocularMeas) 
      mapping[id] = newIri
    }
  }


def cardiacOutput = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000197"))
def calcBlood = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000008"))
ukb.findAll { id, t -> t.category == 'pulse wave analysis' }
  .each { id, t ->
    if(mapping[id]) { return; }
    if(t.label =~ 'cardiac output') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, cardiacOutput) 
      mapping[id] = newIri
    } else {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, calcBlood) 
      mapping[id] = newIri
    }
  }

def mentalMeas = addClass(ecmoPrefix + (++ecmoCounter), "mental health measurement", cMeasureClass)
def depressionMeas = addClass(ecmoPrefix + (++ecmoCounter), "depression measurement", mentalMeas)
ukb.findAll { id, t -> t.category == 'mental health' || t.category == 'unusual and psychotic experiences' || t.category == 'anxiety' }
  .each { id, t ->
    if(mapping[id]) { return; }
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, mentalMeas) 
    mapping[id] = newIri
  }
ukb.findAll { id, t -> t.category == 'depression' }
  .each { id, t ->
    if(mapping[id]) { return; }
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, depressionMeas) 
    mapping[id] = newIri
  }

def ecgParent = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000230"))
ukb.findAll { id, t -> t.category =~ 'ecg' }
  .each { id, t ->
    if(mapping[id]) { return; }
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, ecgParent) 
    mapping[id] = newIri
  }
 
def maternityMeas = addClass(ecmoPrefix + (++ecmoCounter), "maternity measurement", cMeasureClass)
ukb.findAll { id, t -> t.category =~ 'summary maternity' }
  .each { id, t ->
    if(mapping[id]) { return; }
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, maternityMeas) 
    mapping[id] = newIri
  }

def defRate = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000998"))
def defMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000997"))
ukb.findAll { id, t -> t.category =~ 'digestive health' }
  .each { id, t ->
    if(mapping[id]) { return; }
    if(t.label =~ 'bowels opened') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, defMeas) 
      mapping[id] = newIri
    } else {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, defMeas) 
      mapping[id] = newIri
    }
  }

def sunExpMeas = addClass(ecmoPrefix + (++ecmoCounter), "sun exposure measurement", cMeasureClass)
ukb.findAll { id, t -> t.category =~ 'sun exposure' }
  .each { id, t ->
    if(mapping[id]) { return; }
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, sunExpMeas) 
    mapping[id] = newIri
  }

mapping['22507'] = 'http://reality.rehab/ECMO_354'

ukb.findAll { id, t -> t.category == 'medical information' }
  .each { id, t ->
    if(mapping[id]) { return; }
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, smokMeas) 
    mapping[id] = newIri
  }

ukb.findAll { id, t -> t.category == 'medical conditions' }
  .each { id, t ->
    if(mapping[id]) { return; }
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, ageParentClass) 
    mapping[id] = newIri
  }

def liverMeas = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0001064"))
ukb.findAll { id, t -> t.category == 'liver mri' }
  .each { id, t ->
    if(mapping[id]) { return; }
    def newIri = ecmoPrefix + (++ecmoCounter)
    addClass(newIri, t.label, liverMeas) 
    mapping[id] = newIri
  }

mapping['22424'] = 'http://purl.obolibrary.org/obo/CMO_0000197'
def lvMorph = factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/CMO_0000951"))
ukb.findAll { id, t -> t.category == 'left ventricular size and function' }
  .each { id, t ->
    if(mapping[id]) { return; }
    if(t.label =~ 'volume') {
      def newIri = ecmoPrefix + (++ecmoCounter)
      addClass(newIri, t.label, lvMorph) 
      mapping[id] = newIri
    } else {
      println t
    }
  }


def c = 0
def groups = [:]
ukb.each { id, t ->
  if(!mapping[id]) {
    if(!groups[t.category]) {
      groups[t.category] = 0
    }
    groups[t.category]++
    c++
  }
}
println c
println groups.sort { it.getValue() }
println "mapping size: (${mapping.size()}/${ukb.size()})"

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
