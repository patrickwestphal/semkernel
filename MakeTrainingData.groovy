import groovy.lang.GrabConfig
import groovy.lang.Grab

@Grapes([
	@Grab(group='net.sourceforge.owlapi', module='owlapi-api', version='4.0.0'),
	@Grab(group='net.sourceforge.owlapi', module='owlapi-apibinding', version='4.0.0'),
	@Grab(group='net.sourceforge.owlapi', module='owlapi-impl', version='4.0.0'),
	@Grab(group='net.sourceforge.owlapi', module='owlapi-parsers', version='4.0.0'),
	@Grab(group='log4j', module='log4j', version='1.2.17'),
	@Grab(group='org.semanticweb.elk', module='elk-owlapi', version='0.4.1'),
	@Grab(group='org.slf4j', module='slf4j-log4j12', version='1.7.10')
])

import org.apache.log4j.Logger
import org.apache.log4j.Level
import org.semanticweb.elk.owlapi.ElkReasonerFactory
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.OWLDataFactory
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.model.OWLOntologyManager
import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.reasoner.ConsoleProgressMonitor
import org.semanticweb.owlapi.reasoner.InferenceType
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory
import org.semanticweb.owlapi.reasoner.SimpleConfiguration

/* ****************************************************************************
 * set up cli argument paersing and logger
 */

def cli = new CliBuilder()
cli.with {
usage: 'Self'
  h longOpt:'help', 'this information'
  s longOpt:'search-class', 'URI of phenotype class to build a classifier for', args:1, required:true
  o longOpt:'output', 'output file', args:1, required:true
  r longOpt:'ratio', 'ratio of negative to positive (default: use all)', args:1, required:false
  f longOpt:'flat', 'use flat feature vector (libSVM style)', args:0, required:false
  //  "1" longOpt:'pmi', 'min PMI', args:1, required:true
  //  "2" longOpt:'lmi', 'min LMI', args:1, required:true
}

def opt = cli.parse(args)
if( !opt ) {
  //  cli.usage()
  return
}
if( opt.h ) {
    cli.usage()
    return
}

def searchClass = opt.s

def fout = new PrintWriter(new BufferedWriter(new FileWriter(opt.o)))

Boolean flat = false
if (opt.f) {
  flat=true
}
Double ratio = -1
if (opt.r) { 
  ratio = new Double(opt.r) 
}

def classifierFor = new TreeSet()
classifierFor.add(searchClass)

Logger logger = Logger.getLogger('MakeTrainingData')
logger.setLevel(Level.INFO)

/* ****************************************************************************
 * load ontology
 */

String mpFilePath = 'mp.obo'
logger.info("Loading ontology file $mpFilePath ...")
OWLOntologyManager manager = OWLManager.createOWLOntologyManager()
OWLOntology ont = manager.loadOntologyFromOntologyDocument(new File(mpFilePath))
logger.info('-Done-')

logger.info('Building class hierarchy...')
OWLDataFactory fac = manager.getOWLDataFactory()
ConsoleProgressMonitor progressMonitor = new ConsoleProgressMonitor()
OWLReasonerConfiguration config = new SimpleConfiguration(progressMonitor)
OWLReasonerFactory fac1 = new ElkReasonerFactory()
OWLReasoner reasoner = fac1.createReasoner(ont, config)

reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY)
logger.info('-Done-')

/* ****************************************************************************
 * set up class mappings and classes to use
 */

logger.info('Getting all subclasses of the search class...')
def cl = fac.getOWLClass(IRI.create(searchClass))
reasoner.getSubClasses(cl, false).getFlattened().each { sc ->
  classifierFor.add(sc.toString().replaceAll("<","").replaceAll(">",""))
}
logger.info('-Done-')

def map = [:].withDefault { new TreeSet() }

// get the file here: ftp://ftp.informatics.jax.org/pub/reports/gene_association.mgi
String geneAssociationFilePath = 'gene_association.mgi'
logger.info("Reading MGI IDs from $geneAssociationFilePath ...")
new File(geneAssociationFilePath).splitEachLine("\t") { line ->
  if (! line[0].startsWith("!")) {
    def gid = line[1]
    def got = line[4].replaceAll(":","_")
    def evidence = line[6]
    if (evidence != "ND") {
      got = "http://purl.obolibrary.org/obo/"+got
      map[gid].add(got)
    }
  }
}
logger.info('-Done-')

String mousePhenotypesFilePath = 'mousephenotypes.txt'
logger.info("Reading class mappings from $mousePhenotypesFilePath ...")
def pmap = [:].withDefault { new LinkedHashSet() }
new File(mousePhenotypesFilePath).splitEachLine("\t") { line ->
  def mgiid = line[0]
  if (mgiid in map.keySet()) {
    def pid = line[1]
    if (pid) {
      pid = pid.replaceAll(":","_")
      pid = "http://purl.obolibrary.org/obo/"+pid
      pmap[mgiid].add(pid)
    }
  }
}
logger.info('-Done-')

/* ****************************************************************************
 * writing results to file
 */

logger.info('Building positives and negatives lists')
List negatives = []
List positives = []
pmap.each { k, pset ->
  def s = ""
  if (classifierFor.intersect(pset).size()>0) {
    s = "1"
  } else {
    s = "0"
  }
  def gos = map[k]
  gos.each { s+=("\t$it") }
  if (classifierFor.intersect(pset).size()>0) {
    positives.add(s)
  } else {
    negatives.add(s)
  }
}
logger.info('-Done-')

logger.info("Writing results to file ($opt.o)...")
positives.each { fout.println(it) }
Collections.shuffle(negatives)
def posSize = positives.size()

// trim number of negative examples to fit the ratio specified in the cli args
def cutoff = Math.round(ratio * posSize)

if (cutoff > negatives.size()) {
  ratio = -1
}
if (ratio > 0) {
  negatives[0..cutoff].each { 
    fout.println(it)
  }
} else {
  negatives.each {
    fout.println(it)
  }
}
logger.info('-Done-')

fout.flush()
fout.close()
