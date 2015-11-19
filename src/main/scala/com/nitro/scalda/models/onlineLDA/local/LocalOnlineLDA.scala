package com.nitro.scalda.models.onlineLDA.local

import java.io._

import breeze.linalg.{DenseMatrix, sum, Axis}
import breeze.numerics.{lgamma, abs, exp}
import breeze.stats.distributions.{Gamma => G}
import breeze.stats.mean
import com.nitro.scalda.Utils
import com.nitro.scalda.models._
import com.nitro.scalda.tokenizer.StanfordLemmatizer

import scala.util.Try

object LocalOnlineLDA {

  def apply(p: OnlineLDAParams) = {
    new LocalOnlineLDA(p)
  }

  //If you are only wanting to infer topic proportions, parameters can be set to default
  def apply() = {

    println("Warning: No LDA parameters specified so defaults are being used.  This is only advisable for topic " +
      "proportions inference.")

    val p = OnlineLDAParams(
      vocabulary = List.empty[String],
      alpha = 0.0,
      eta = 0.0,
      decay = 1.0,
      learningRate = 0.0,
      maxIter = 100,
      convergenceThreshold = 0.001,
      numTopics = 0,
      totalDocs = 0)

    new LocalOnlineLDA(p)
  }
}

/**
 * Local, non-distributed version of the online LDA algorithm
 * @param params online LDA parameters.  These are static parameters
 */
class LocalOnlineLDA(params: OnlineLDAParams) extends OnlineLDA {

  override type MinibatchSStats = MbSStats[DenseMatrix[Double], DenseMatrix[Double]]
  override type SStats = ModelSStats[DenseMatrix[Double]]
  override type TopicMatrix = DenseMatrix[Double]

  type BOWMinibatch = Seq[Document]
  type MinibatchIterator = Iterator[Seq[String]]

  /**
   * Process a minibatch of documents to infer topic proportions and "intermediate" topics.
   * @param mb Minibatch of documents.
   * @param lambda Parameter that is a function of overall topics learned so far.
   * @return Sufficient statistics from this minibatch.
   */
  def eStep(mb: BOWMinibatch,
            lambda: TopicMatrix,
            gamma: TopicMatrix,
            perplexity: Boolean = false): MinibatchSStats = {

    val numDocs = mb.size
    val eLogBeta = Utils.dirichletExpectation(lambda)
    val expELogBeta = exp(eLogBeta)
    val numTopics = expELogBeta.rows

    val mbTopicMatrix = DenseMatrix.zeros[Double](numTopics, expELogBeta.cols)
    val expELogTheta = exp(Utils.dirichletExpectation(gamma))

    //iterate through documents in minibatch
    for ((doc, docId) <- mb.zipWithIndex) {

      val ids = doc.wordIds
      val cts = DenseMatrix(doc.wordCts.map(_.toDouble))

      var gammaDoc = gamma(docId, ::).t.toDenseMatrix
      var expELogThetaDoc = expELogTheta(docId, ::).t.toDenseMatrix
      val expELogBetaDoc = expELogBeta(::, ids).toDenseMatrix
      var phiNorm = expELogThetaDoc * expELogBetaDoc + 1e-100

      var convergence = false
      var numIter = 0

      //Iterate until variational parameters converge
      while ((numIter < params.maxIter) && !convergence) {
        val lastGammaD = gammaDoc
        gammaDoc = (expELogThetaDoc :* (cts / phiNorm.t) * expELogBetaDoc.t) + params.alpha
        expELogThetaDoc = exp(Utils.dirichletExpectation(gammaDoc))
        phiNorm = expELogThetaDoc * expELogBetaDoc + 1e-100
        if (mean(abs(gammaDoc - lastGammaD)) < params.convergenceThreshold) convergence = true
        numIter += 1
      }

      gamma(docId, ::) := gammaDoc.toDenseVector.t

      val docTopicUpdate = expELogThetaDoc.t * (cts / phiNorm)

      for ((id, idx) <- ids.zipWithIndex) {
        mbTopicMatrix(::, id) :+= docTopicUpdate(::, idx)
      }

    }

    MbSStats(
      mbTopicMatrix :* expELogBeta,
      gamma,
      numDocs
    )
  }

  /**
   * Step merging overall topics with "intermediate" topics learned from the last minibatch.
   * @param model current overall topics learned so far.
   * @param mbSStats sufficient statistics from the last minibatch ("intermediate topics").
   * @return An updated model.
   */
  def mStep(model: SStats,
            mbSStats: MinibatchSStats): SStats = {

    val rho = math.pow(params.decay + model.numUpdates, -params.learningRate)

    //merge overall topics with minibatch updates
    val updatedLambda = (model.lambda * (1 - rho)) + rho * ((mbSStats.topicUpdates * (params.totalDocs.toDouble / mbSStats.mbSize)) + params.eta)

    val numProcessed = model.numUpdates + 1

    model.copy(lambda = updatedLambda,
      numUpdates = numProcessed)
  }

  /**
   * Learn a topic model from a minibatch iterator over a corpus of documents.
   * @param mbIt Minibatch iterator over a corpus of documents.
   * @return A learned LDA topic model.
   */
  def inference(mbIt: MinibatchIterator): SStats = {

    //Initialize model settings
    val vocabMapping = Utils.mapVocabId(params.vocabulary)

    val lambda = new DenseMatrix[Double](params.numTopics,
      vocabMapping.size,
      G(100.0, 1.0 / 100.0).sample(params.numTopics * vocabMapping.size).toArray)

    var curModel = ModelSStats[DenseMatrix[Double]](lambda, vocabMapping, 0)

    val lemmatizer = params.lemmatize match {
      case true => Some(StanfordLemmatizer())
      case _ => None
    }

    var counter = 0.0

    //iterate through each minibatch
    while (mbIt.hasNext) {

      counter += 1

      if (counter % 5 == 0) {
        println(s"${counter} minibatches processed!")
      }

      //put minibatch in BOW form
      val rawMinibatch = mbIt.next()
      val bowMinibatch = rawMinibatch.map(doc => Utils.toBagOfWords(doc, vocabMapping, lemmatizer))

      val initialGamma = new DenseMatrix[Double](bowMinibatch.size,
        params.numTopics,
        G(100.0, 1.0 / 100.0).sample(bowMinibatch.size * params.numTopics).toArray)

      //perform e-step
      val mbSStats = eStep(bowMinibatch, curModel.lambda, initialGamma)

      //compute perplexity of next minibatch if enabled
      if (params.perplexity) {
        val score = perplexity(bowMinibatch,
          mbSStats.topicProportions,
          curModel.lambda)

        val mbWordCt = sum(bowMinibatch.flatMap(_.wordCts))
        val wordScale = (score * bowMinibatch.size) / (params.totalDocs * mbWordCt.toDouble)

        println(s"per-word perplexity: ${exp(-wordScale)}")
      }

      //update current model with m-step
      curModel = mStep(curModel, mbSStats)
    }

    curModel
  }

  /**
   * Minibatch perplexity computation.
   * @param mb minibatch of wordIds/counts.
   * @param mbGamma Gamma from e-step applied to this minibatch.
   * @param lambda current lambda value (not yet updated from this minibatch).
   * @return perplexity value.
   */
  def perplexity(mb: BOWMinibatch,
                 mbGamma: TopicMatrix,
                 lambda: TopicMatrix): Double = {

    val eLogTheta = Utils.dirichletExpectation(mbGamma)
    val eLogBeta = Utils.dirichletExpectation(lambda)

    var perplexityScore = 0.0

    for ((doc, docId) <- mb.zipWithIndex) {

      val eLogThetaDoc = eLogTheta(docId, ::).t

      perplexityScore += sum(
        doc.wordIds.zip(doc.wordCts).map {
          case (wordId, wordCt) => Utils.logSumExp(eLogThetaDoc + eLogBeta(::, wordId)) * wordCt.toDouble
        }
      )

    }

    perplexityScore += sum(mbGamma.map(el => params.alpha - el) :* eLogTheta)
    perplexityScore += sum(lgamma(mbGamma) - lgamma(params.alpha))
    perplexityScore += sum(lgamma(params.alpha * params.numTopics) - lgamma(sum(mbGamma, Axis._1)))
    perplexityScore *= params.totalDocs / mb.size.toDouble
    perplexityScore += sum(lambda.map(el => params.eta - el) :* eLogBeta)
    perplexityScore += sum(lgamma(lambda) - lgamma(params.eta))
    perplexityScore += sum(lgamma(params.eta * params.vocabulary.size) - lgamma(sum(lambda, Axis._1)))

    perplexityScore
  }

  /**
   * Print top 10 words by probability from each of the topics of a given topic model.
   * @param model A learned topic model
   */
  def printTopics(model: SStats): Unit = {

    val reverseVocab = model.vocabMapping.map(_.swap)
    val lambda = model.lambda

    //iterate through topics and print high probability words
    for (t <- 0 to lambda.rows - 1) {

      val topic = lambda(t, ::).t

      val normalizer = sum(topic)

      val topTopics = topic
        .toArray
        .map(prob => prob / normalizer)
        .zipWithIndex
        .sortBy(-_._1)
        .map { case (prob, wordId) => (reverseVocab(wordId), prob) }
        .take(10)

      println(s"Topic #${t}: " + topTopics.toList)
    }

  }

  /**
   * Given a document and a topic model, infer the proportions of each topic in the document.
   * @param doc A document.
   * @param model A learned topic model.
   * @return The topic proportions for the given document.
   */
  def topicProportions(doc: String,
                       model: SStats,
                       lemmatizer: Option[StanfordLemmatizer] = None): Array[(Double, Int)] = {

    lazy val expElogBeta = exp(Utils.dirichletExpectation(model.lambda))

    val bowDoc = Utils.toBagOfWords(doc, model.vocabMapping, lemmatizer)

    val initialGamma = new DenseMatrix[Double](1,
      params.numTopics,
      G(100.0, 1.0 / 100.0).sample(params.numTopics).toArray)

    val docSStats = eStep(Seq(bowDoc), expElogBeta, initialGamma)

    val normalizer = sum(docSStats.topicProportions)

    docSStats.topicProportions(0, ::)
      .t
      .toArray
      .map(_ / normalizer)
      .zipWithIndex
  }

  /**
   * Compute the similarity between two documents by computing the Euclidean distance between their topic proportions.
   * @param doc1 Document 1 to compare.
   * @param doc2 Document 2 to compare.
   * @param model Model to draw topic proportions.
   * @return Document similarity value.
   */
  def documentSimilarity(doc1: String,
                         doc2: String,
                         model: SStats): Double = {

    val lemmatizer = params.lemmatize match {
      case true => Some(StanfordLemmatizer())
      case _ => None
    }

    val doc1TopicProportions = topicProportions(doc1, model, lemmatizer)
      .map(_._1)
      .map(x => if (x < 0.1) 0.0 else x)

    val doc2TopicProportions = topicProportions(doc2, model, lemmatizer)
      .map(_._1)
      .map(x => if (x < 0.1) 0.0 else x)

    Utils.euclideanDistance(doc1TopicProportions, doc2TopicProportions)
  }

  /**
   * Save a learned topic model.
   * @param model Model to save.
   * @param saveLocation Save location.
   */
  def saveModel(model: SStats, saveLocation: String): Unit = {

    val fos = new FileOutputStream(new File(saveLocation))
    val oos = new ObjectOutputStream(fos)

    oos.writeObject(model)
    oos.close()
  }

  /**
   * Load a saved topic model from save location.
   * @param saveLocation Location saved.
   * @return Loaded model.
   */
  def loadModel(saveLocation: String): Try[SStats] = {

    val fis = new FileInputStream(new File(saveLocation))
    loadModel(fis)
  }

  /**
   * Load a saved topic model from an input stream.
   * @param modelIS input stream for model.
   * @return loaded model.
   */
  def loadModel(modelIS: InputStream): Try[SStats] = {

    val ois = new ObjectInputStream(modelIS)
    Try(ois.readObject().asInstanceOf[SStats])
  }

}

