@Grapes([
	@Grab(group='commons-cli', module='commons-cli', version='1.2'),
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
import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.model.OWLDataFactory
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.model.OWLOntologyManager
import org.semanticweb.owlapi.reasoner.ConsoleProgressMonitor
import org.semanticweb.owlapi.reasoner.InferenceType
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory
import org.semanticweb.owlapi.reasoner.SimpleConfiguration

/* ****************************************************************************
 * set up argument parsing and logging
 */
def cli = new CliBuilder()
cli.with {
usage: 'Self'
  h longOpt:'help', 'this information'
  s longOpt:'search-class', 'URI of phenotype class to build a classifier for', args:1, required:true
  o longOpt:'output', 'output file', args:1, required:true
  r longOpt:'ratio', 'ratio of negative to positive (default: use all)', args:1, required:false
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
Double ratio = -1
if (opt.r) { ratio = new Double(opt.r) }

Logger logger = Logger.getLogger('MakePredictionData')
logger.setLevel(Level.INFO)

/* ****************************************************************************
 * set up ontology
 */

def classifierFor = new TreeSet()
classifierFor.add(searchClass)

String mpFilePath = 'mp.obo'
logger.info("Reading ontology file $mpFilePath ...")
OWLOntologyManager manager = OWLManager.createOWLOntologyManager()
OWLOntology ont = manager.loadOntologyFromOntologyDocument(new File(mpFilePath))
logger.info('-Done-')

logger.info('Computing class hierarchy...')
OWLDataFactory fac = manager.getOWLDataFactory()
ConsoleProgressMonitor progressMonitor = new ConsoleProgressMonitor()
OWLReasonerConfiguration config = new SimpleConfiguration(progressMonitor)
OWLReasonerFactory reasonerFactory = new ElkReasonerFactory()
OWLReasoner reasoner = reasonerFactory.createReasoner(ont, config)

reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY)
logger.info('-Done-')

/* ****************************************************************************
 * set up class mappings and classes to use
 */
logger.info("Getting subclasses of $searchClass ...")
def cl = fac.getOWLClass(IRI.create(searchClass))
reasoner.getSubClasses(cl, false).getFlattened().each { sc ->
  classifierFor.add(sc.toString().replaceAll("<","").replaceAll(">",""))
}
logger.info('-Done-')


def map = [:].withDefault { new TreeSet() }

def classes = new TreeSet()

// get the file here: ftp://ftp.informatics.jax.org/pub/reports/gene_association.mgi
String geneAssociationFilePath = 'gene_association.mgi'

// build MGI ID to URL mapping (e.g. MGI:1918911 => http://purl.obolibrary.org/obo/MGI_1918911)
logger.info("Reading MGI IDs from $geneAssociationFilePath ...")
new File(geneAssociationFilePath).splitEachLine("\t") { line ->
  if (! line[0].startsWith("!")) {
    def gid = line[1]  // MGI ID, e.g. MGI:1918911
    def got = line[4].replaceAll(":","_")
    def evidence = line[6]
    if (evidence != "ND") {
      got = "http://purl.obolibrary.org/obo/"+got
      map[gid].add(got)
      classes.add(got)
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

logger.info("Writing class mapping to file ($opt.o)...")
// header
fout.print("map")
classes.each { fout.print("\t$it") }
fout.println ("")
logger.info('-Done-')

logger.info("Writing GO classes and annotations (positive/negative example) to file ($opt.o)...")
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

logger.info("Writing list of positive and negative examples' URIs to file ($opt.o)...")
positives.each { fout.println(it) }
Collections.shuffle(negatives)
def posSize = positives.size()
def cutoff = Math.round(ratio * posSize)

// trim number of negative examples to fit the ratio specified in the cli args
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
