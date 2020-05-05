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
import org.semanticweb.elk.owlapi.*
import org.semanticweb.elk.reasoner.config.*
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.reasoner.*
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary
import org.semanticweb.owlapi.model.*
import org.semanticweb.owlapi.io.*
import org.semanticweb.owlapi.owllink.*
import org.semanticweb.owlapi.util.*
import org.semanticweb.owlapi.search.*
import org.semanticweb.owlapi.manchestersyntax.renderer.*
import org.semanticweb.owlapi.reasoner.structural.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import groovyx.gpars.*
import org.codehaus.gpars.*

def manager = OWLManager.createOWLOntologyManager()
fac = manager.getOWLDataFactory()
def cmo = manager.loadOntologyFromOntologyDocument(new File("inputs/cmo.owl"))

def labelMap = { ont ->
  def map = [:]
  def fac = OWLManager.createOWLOntologyManager().getOWLDataFactory()
  ont.getClassesInSignature(true).each { cl ->
    EntitySearcher.getAnnotations(cl, ont, fac.getRDFSLabel()).each { anno ->
      OWLAnnotationValue val = anno.getValue()
      if (val instanceof OWLLiteral) {
        if(!map.containsKey(cl)) {
          map[cl] = []
        }

        def lString = val.getLiteral().toLowerCase().replaceAll("[^a-zA-Z ]", "").replaceAll('\\s+', ' ')

        map[cl] << [
          label: lString,
          phrases: createPhrases(lString)
        ]
      }
    }
  }
  map
}
def classLabels = labelMap(cmo)

def createPhrases(str) {
  def lst = str.split(' ')
  def phrases = [] 

  (0..lst.size()).each {
    lst.eachWithIndex { w, i ->
      if(i+it < lst.size()) {
        phrases << lst[i..i+it].join(' ')
      }
    } 
  }

  phrases.unique(false)
}

def ukb_traits = []
['./ukb_traits/one_traits.tsv', './ukb_traits/two_traits.tsv'].each {
  new File(it).splitEachLine('\t') { f ->
    if(f[0] == 'Field ID') { return; } 
    ukb_traits << [
      id: f[0], 
      description: f[1], 
      category: f[2]
    ]
  }
}

def output = 'id\tlabel\tmatch quality\ttermid\tcmo label'

ukb_traits.each { t ->
  if(!t.description) { return; }
  def mText = t.description.toLowerCase().replaceAll("[^a-zA-Z ]", "").replaceAll('\\s+', ' ')
  def mPhrases = createPhrases(mText)

  def bestMatch = [ count: 0, firstLabel: null, matchedLabel: null, iri: null ]
  classLabels.each { iri, labels ->
    labels.each { l ->
      def matches = l.phrases.findAll { p -> mPhrases.contains(p) }.collect { 
      it.split(' ').size() }.max()
      if(matches > bestMatch.count) {
        bestMatch = [
          count: matches,
          firstLabel: labels[0].label,
          matchedLabel: l.label,
          iri: iri
        ]
      }
    }
  }

  if(bestMatch.count != 0 && bestMatch.count/mText.split(' ').size() >= 0.5 || mText.indexOf('bmd') != -1) {
    output += "\n$t.id\t$t.description\t$bestMatch.count\t$bestMatch.iri\t$bestMatch.firstLabel"
  } else {
    output += "\n$t.id\t$t.description\t$bestMatch.count\tN/A\tN/A"
  }
}

new File('mapping.tsv').text = output
println 'done'


