package edu.nju.pasalab.marlin.matrix

import java.io.IOException
import java.util.Arrays
import java.util.Calendar
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{ FileSystem, Path }

import scala.collection.mutable.ArrayBuffer
import scala.collection.parallel.mutable.ParArray

import org.apache.hadoop.io.{ Text, NullWritable }
import org.apache.hadoop.mapred.TextOutputFormat
import org.apache.log4j.{ Logger, Level }
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.Logging
import org.apache.spark.storage.StorageLevel

import edu.nju.pasalab.marlin.utils.MTUtils

import breeze.linalg.{
  DenseMatrix => BDM,
  DenseVector => BDV,
  axpy => brzAxpy,
  svd => brzSvd,
  LU => brzLU,
  inv => brzInv,
  cholesky => brzCholesky
}
import breeze.numerics.{ sqrt => brzSqrt }
import com.github.fommil.netlib.BLAS.{ getInstance => blas }

import com.github.fommil.netlib.ARPACK
import org.netlib.util.{ intW, doubleW }

import scala.language.implicitConversions

class DenseVecMatrix(
  private[marlin] val rows: RDD[(Long, DenseVector)],
  private var nRows: Long,
  private var nCols: Long) extends DistributedMatrix with Logging {

  private var resultCols: Long = 0
  def this(rows: RDD[(Long, DenseVector)]) = this(rows, 0L, 0)

  def this(sc: SparkContext, array: Array[Array[Double]], partitions: Int = 2) {
    this(sc.parallelize(array.zipWithIndex.
      map { case (t, i) => (i.toLong, Vectors.dense(t)) }, partitions))
  }

  override def numCols(): Long = {
    if (nCols <= 0) {
      // Calling `first` will throw an exception if `rows` is empty.
      nCols = rows.first()._2.size
    }
    nCols
  }

  override def numRows(): Long = {
    if (nRows <= 0L) {
      // Reduce will throw an exception if `rows` is empty.
      nRows = rows.map(_._1).reduce(math.max) + 1L
    }
    nRows
  }

  /** Collects data and assembles a local dense breeze matrix (for test only). */
  override private[matrix] def toBreeze(): BDM[Double] = {
    val m = numRows().toInt
    val n = numCols().toInt
    val mat = BDM.zeros[Double](m, n)
    rows.collect().foreach {
      case (rowIndex, vector) =>
        val i = rowIndex.toInt
        vector.toBreeze.activeIterator.foreach {
          case (j, v) =>
            mat(i, j) = v
        }
    }
    mat
  }

  /** Get the numbers of cores across the cluster */
  private[marlin] def getClusterCores(): Int = {
    val sc = rows.context
    val cores = if (!sc.getConf.getOption("spark.default.parallelism").isEmpty) {
      sc.getConf.get("spark.default.parallelism").toInt
    } else {
      sc.defaultMinPartitions
    }
    cores
  }
  /**
   * This function is used to satisfy the
   * @param other
   * @param cores
   * @return
   */
  def multiply(other: DistributedMatrix, cores: Int): BlockMatrix = {
    multiply(other, cores, 300)
  }

  /**
   * Matrix-matrix multiply
   *
   * @param other another matrix
   * @param cores the real num of cores you set in the environment
   * @param broadcastThreshold the threshold of broadcasting variable, default num is 300 MB,
   *                           user can set it, the unit of this parameter is MB
   * @return result in BlockMatrix type
   */

  final def multiply(other: DistributedMatrix,
    cores: Int,
    broadcastThreshold: Int = 300): BlockMatrix = {
    other match {
      case that: DenseVecMatrix => {
        require(numCols == that.numRows(),
          s"Dimension mismatch: ${numCols()} vs ${that.numRows()}")

        val broadSize = broadcastThreshold * 1024 * 1024 / 8
        if (that.numRows() * that.numCols() <= broadSize) {
          val parallelism = math.min(8 * cores, numRows() / 2).toInt
          multiplyBroadcast(that, parallelism, (parallelism, 1, 1), "broadcastB")
        } else if (numRows() * numCols() <= broadSize) {
          val parallelism = math.min(8 * cores, that.numRows() / 2).toInt
          multiplyBroadcast(that, parallelism, (1, 1, parallelism), "broadcastA")
        } else if (0.8 < (numRows() * that.numCols()).toDouble / (numCols() * numCols()).toDouble
          && (numRows() * that.numCols()).toDouble / (numCols() * numCols()).toDouble < 1.2
          && numRows() / numCols() < 1.2
          && numRows() / numCols() > 0.8) {
          multiplyHama(that, math.floor(math.pow(3 * cores, 1.0 / 3.0)).toInt)
        } else {
          multiplyCarma(that, cores)
        }
      }
      case that: BlockMatrix => {
        val broadSize = broadcastThreshold * 1024 * 1024 / 8
        if (that.numRows() * that.numCols() <= broadSize && that.numBlksByRow() == 1) {
          val broadBDM = rows.context.broadcast(this.toBreeze())
          val result = that.blocks.mapPartitions(iter => {
            iter.map(t => {
              (t._1, (broadBDM.value * t._2).asInstanceOf[BDM[Double]])
            })
          })
          new BlockMatrix(result, numRows(), that.numCols(), that.numBlksByRow(), that.numBlksByCol())
        } else {
          multiply(that.toDenseVecMatrix(), cores)
        }
      }
    }

  }

  /**
   * Column bind from two matrices, and create a new matrix, just like cBind in R
   * @param other another matrix in DenseVecMatrix format
   * @return
   */
  def cBind(other: DistributedMatrix): DistributedMatrix = {
    require(numRows() == other.numRows(), s"Dimension mismatch:  ${numRows()} vs ${other.numRows()}")
    other match {
      case that: DenseVecMatrix => {
        val result = rows.join(that.rows).map(t => {
          (t._1, new DenseVector(t._2._1.toArray ++: t._2._2.toArray))
        })
        new DenseVecMatrix(result, numRows(), numCols() + that.numCols())
      }
      case that: BlockMatrix => {
        val thatDenVec = that.toDenseVecMatrix()
        cBind(thatDenVec)
      }
      case that: DistributedMatrix => {
        throw new IllegalArgumentException("Do not support this type " + that.getClass + " for cBind operation")
      }
    }
  }

  /**
   * A matrix multiply another DenseVecMatrix
   *
   * @param other another matrix in DenseVecMatrix format
   * @param blkNum is the split nums of submatries, if you set it as 10,
   *               which means you split every original large matrix into 10*10=100 blocks.
   *               The smaller this argument, the biger every worker get submatrix.
   * @return a distributed matrix in BlockMatrix type
   */
  private[marlin] def multiplyHama(other: DenseVecMatrix, blkNum: Int): BlockMatrix = {
    val otherRows = other.numRows()
    require(numCols == otherRows, s"Dimension mismatch: ${numCols} vs ${otherRows}")

    resultCols = other.numCols()
    val thisBlocks = asInstanceOf[DenseVecMatrix].toBlockMatrix(blkNum, blkNum)
    val otherBlocks = other.asInstanceOf[DenseVecMatrix].toBlockMatrix(blkNum, blkNum)
    thisBlocks.multiply(otherBlocks, blkNum * blkNum * blkNum)
  }

  /**
   * refer to CARMA, implement the dimension-split ways
   *
   * @param other matrix to be multiplied, in the form of DenseVecMatrix
   * @param cores all the num of cores cross the cluster
   * @return a distributed matrix in BlockMatrix type
   */

  private[marlin] def multiplyCarma(other: DenseVecMatrix, cores: Int): BlockMatrix = {
    val otherRows = other.numRows()
    require(numCols == otherRows, s"Dimension mismatch: ${numCols} vs ${otherRows}")
    val (mSplitNum, kSplitNum, nSplitNum) =
      MTUtils.splitMethod(numRows(), numCols(), other.numCols(), cores)
    val thisCollects = asInstanceOf[DenseVecMatrix].toBlockMatrix(mSplitNum, kSplitNum)
    val otherCollects = other.asInstanceOf[DenseVecMatrix].toBlockMatrix(kSplitNum, nSplitNum)
    thisCollects.multiply(otherCollects, cores)
  }

  /**
   * refer to CARMA, implement the dimension-split ways
   *
   * @param other matrix to be multiplied, in the form of DenseVecMatrix
   * @param parallelism all the num of cores cross the cluster
   * @param mode whether broadcast A or B
   * @return
   */

  private[marlin] def multiplyBroadcast(other: DenseVecMatrix,
    parallelism: Int,
    splits: (Int, Int, Int), mode: String): BlockMatrix = {
    val otherRows = other.numRows()
    require(numCols == otherRows, s"Dimension mismatch: ${numCols} vs ${otherRows}")
    val thisCollects = asInstanceOf[DenseVecMatrix].toBlockMatrix(splits._1, splits._2)
    val otherCollects = other.asInstanceOf[DenseVecMatrix].toBlockMatrix(splits._2, splits._3)
    thisCollects.multiplyBroadcast(otherCollects, parallelism, splits, mode)
  }

  /**
   * multiply a elementary matrix on the left to apply row switching transformations
   *
   * @param permutation the elementary row switching matrix
   * @return the multiplication
   */
  private[marlin] def rowExchange(permutation: Array[Int]): DenseVecMatrix = {
    // val permPair = (0 until permutation.length).toArray.zip(permutation)
    require(numRows == permutation.length,
      s"Dimension mismatch, row permutation matrix: ${permutation.length} vs $nRows")
    val index = rows.context.parallelize(permutation.zipWithIndex.toSeq, getClusterCores())
      .map(t => (t._1.toLong, t._2.toLong))
    val result = rows.join(index).map(t => (t._2._2, t._2._1))
    new DenseVecMatrix(result, numRows(), numCols())
  }

  /**
   * This is an experimental implementation of block LU decomposition. The method is still in progress.
   * LU decompose this DenseVecMatrix to generate a lower triangular matrix L
   * and a upper triangular matrix U and a permutation matrix. i.e. LU = PA
   *
   * @param mode whether to calculate in a local or a distributed manner
   * @return a triple(lower triangular matrix, upper triangular matrix, row permutation array)
   */
  def blockLUDecompose(mode: String = "auto"): (DenseVecMatrix, DenseVecMatrix, Array[Int]) = {
    def getFactor(n: Long, m: Long): Long = {
      if (n % m == 0) m
      else getFactor(n, m - 1)
    }
    require(numRows == numCols,
      s"currently we only support square matrix: ${numRows} vs ${numCols}")

    object LUmode extends Enumeration {
      val LocalBreeze, DistSpark = Value
    }
    val computeMode = mode match {
      case "auto" => if (numRows > 6000L) {
        LUmode.DistSpark
      } else {
        LUmode.LocalBreeze
      }
      case "breeze" => LUmode.LocalBreeze
      case "dist" => LUmode.DistSpark
      case _ => throw new IllegalArgumentException(s"Do not support mode $mode.")
    }
    val (lower: DenseVecMatrix, upper: DenseVecMatrix, permutation: Array[Int]) = computeMode match {
      case LUmode.LocalBreeze => {
        val brz = toBreeze()
        val lu = brzLU(brz)
        val l = breeze.linalg.lowerTriangular(lu._1).t
        for (i <- 0 until l.rows) {
          l.update(i, i, 1.0)
        }
        val u = breeze.linalg.upperTriangular(lu._1).t
        val uBro = rows.sparkContext.broadcast(u)
        val uDense = rows.mapPartitions(iter => {
          iter.map(t =>
            (t._1, Vectors.dense(uBro.value(::, t._1.toInt).toArray)))
        })
        val lBro = rows.sparkContext.broadcast(l)
        val lDense = rows.mapPartitions(iter => {
          iter.map(t =>
            (t._1, Vectors.dense(lBro.value(::, t._1.toInt).toArray)))
        })
        val pArray = (0 until lu._2.length).toArray
        for (i <- 0 until lu._2.length) {
          val tmp = pArray(i)
          pArray(i) = pArray(lu._2(i) - 1)
          pArray(lu._2(i) - 1) = tmp
        }
        (new DenseVecMatrix(lDense, numRows, numRows), new DenseVecMatrix(uDense, numRows, numRows), pArray)
      }

      case LUmode.DistSpark => {
        //val numSubRows = getFactor(numRows, Math.sqrt(numRows).toLong).toInt
        val numSubRows = 1000
        val numSubRows2 = (numRows - numSubRows).toInt

        val subMatrix11 = getSubMatrix(0, numSubRows - 1, 0, numSubRows.toInt - 1)
        val subMatrix12 = getSubMatrix(0, numSubRows - 1, numSubRows.toInt, numCols.toInt - 1)
        val subMatrix21 = getSubMatrix(numSubRows.toInt, numRows - 1, 0, numSubRows.toInt - 1)
        val subMatrix22 = getSubMatrix(numSubRows.toInt, numRows - 1, numSubRows.toInt, numCols.toInt - 1)
        val (lMatrix11, uMatrix11, pMatrix11) = subMatrix11.blockLUDecompose("auto")

        val u = subMatrix21.rows.context.broadcast(uMatrix11.toBreeze())
        val lMatrix21Rdd = subMatrix21.rows.mapPartitions(iter => {
          iter.map { t =>
            val array = t._2.toArray.clone()
            for (j <- 0 until array.length) {
              for (k <- 0 until j) {
                array(j) = array(j) - u.value.apply(k, j) * array(k)
              }
              array(j) = array(j) / u.value.apply(j, j)
            }
            (t._1, Vectors.dense(array))
          }
        })
        var lMatrix21 = new DenseVecMatrix(lMatrix21Rdd, numSubRows2, numSubRows)

        val tmp = subMatrix12.rowExchange(pMatrix11)
          .transpose().toDenseVecMatrix()
        val l = tmp.rows.context.broadcast(lMatrix11.toBreeze())
        val uMatrix12Rdd = tmp
          .rows.mapPartitions(iter => {
          iter.map { t =>
            val array = t._2.toArray.clone()
            for (j <- 0 until numSubRows) {
              for (k <- 0 until j) {
                array(j) = array(j) - l.value.apply(j, k) * array(k)
              }
              array(j) = array(j) / l.value.apply(j, j)
            }
            (t._1, Vectors.dense(array))
          }
        })
        val uMatrix12 = new DenseVecMatrix(uMatrix12Rdd, numSubRows2, numSubRows).transpose().toDenseVecMatrix()

        val sc = rows.context
        val cores = if (!sc.getConf.getOption("spark.default.parallelism").isEmpty) {
          sc.getConf.get("spark.default.parallelism").toInt
        } else {
          sc.defaultMinPartitions
        }
        val (lMatrix22, uMatrix22, pMatrix22) = subMatrix22
          .subtract(lMatrix21.multiply(uMatrix12, cores)).blockLUDecompose("auto")

        lMatrix21 = lMatrix21.rowExchange(pMatrix22)

        val lUpper = lMatrix11.rows
          .map(t => (t._1, Vectors.fromBreeze(BDV.vertcat(t._2.toBreeze.asInstanceOf[BDV[Double]], BDV.zeros[Double](numSubRows2)))))
        val lLower = lMatrix21.rows
          .join(lMatrix22.rows)
          .map { t =>
          val array1 = t._2._1.toArray
          val array2 = t._2._2.toArray
          (t._1 + numSubRows, Vectors.dense(array1 ++ array2))
        }
        val LMatrix = new DenseVecMatrix(lUpper.union(lLower), numRows(), numRows())

        val UUpper = uMatrix11.rows
          .join(uMatrix12.rows)
          .map { t =>
          val array1 = t._2._1.toArray
          val array2 = t._2._2.toArray
          (t._1, Vectors.dense(array1 ++ array2))
        }
        val ULower = uMatrix22.rows
          .map(t => (t._1 + numSubRows, Vectors.fromBreeze(BDV.vertcat(BDV.zeros[Double](numSubRows), t._2.toBreeze.asInstanceOf[BDV[Double]]))))

        val UMatrix = new DenseVecMatrix(UUpper.union(ULower), numRows(), numRows())

        val PMatrix = pMatrix11 ++ (pMatrix22.map {
          _ + numSubRows
        })

        (LMatrix, UMatrix, PMatrix)
      }
    }
    (lower, upper, permutation)

  }

  /**
   * This function still works in progress. it needs to do more work
   * LU decompose this DenseVecMatrix to generate a lower triangular matrix L
   * and a upper triangular matrix U
   *
   * @return a pair (lower triangular matrix, upper triangular matrix)
   */
  def luDecompose(mode: String = "auto"): (DenseVecMatrix, DenseVecMatrix) = {
    val iterations = numRows
    require(iterations == numCols,
      s"currently we only support square matrix: ${iterations} vs ${numCols}")
    if (!rows.context.getCheckpointDir.isDefined) {
      println("Waning, checkpointdir is not set! We suggest you set it before running luDecopose")
    }
    /*
    object LUmode extends Enumeration {
      val LocalBreeze, DistSpark = Value
    }
    val computeMode = mode match {
      case "auto" => if (iterations > 3200L) {
        LUmode.DistSpark
      } else {
        LUmode.LocalBreeze
      }
      case "breeze" => LUmode.LocalBreeze
      case "dist" => LUmode.DistSpark
      case _ => throw new IllegalArgumentException(s"Do not support mode $mode.")
    }

    val (lower: DenseVecMatrix, upper: DenseVecMatrix, permutation) = computeMode match {
      case LUmode.LocalBreeze =>
        val temp = brzLU(toBreeze())
        val l = breeze.linalg.lowerTriangular(temp._1)
        for (i <- 0 until l.rows) {
          l.update(i, i, 1.0)
        }
        val u = breeze.linalg.upperTriangular(temp._1) 
        (Matrices.fromBreeze(l), Matrices.fromBreeze(u), temp._2)
      case LUmode.DistSpark => 
        
        val subNumRows = numRows / 2;
        val subMatrixA1 = getSubMatrix(0, subNumRows, 0, subNumRows.toInt)
        val subMatrixA2 = getSubMatrix(0, subNumRows, subNumRows.toInt + 1, numCols.toInt)
        val subMatrixA3 = getSubMatrix(subNumRows.toInt + 1, numRows, 0, subNumRows.toInt)
        val subMatrixA4 = getSubMatrix(subNumRows.toInt + 1, numRows, subNumRows.toInt + 1, numCols.toInt)     
        val (l1, u1, p1) = subMatrixA1.luDecompose("auto") 
        val u2 = 
        
    }
    * 
    */

    /**copy construct a DenseVecMatrix to maintain the original matrix **/
    var mat = new DenseVecMatrix(rows.map(t => {
      val array = Array.ofDim[Double](numCols().toInt)
      val v = t._2.toArray
      for (k <- 0 until v.length) {
        array(k) = v.apply(k)
      }
      (t._1, Vectors.dense(array))
    }))

    val num = iterations.toInt
    var lowerMat = MTUtils.zerosDenVecMatrix(rows.context, numRows(), numCols().toInt)
    for (i <- 0 until num) {
      val vector = mat.rows.filter(t => t._1.toInt == i).map(t => t._2).first()
      val c = mat.rows.context.broadcast(vector.apply(i))
      val broadVec = mat.rows.context.broadcast(vector)
      //TODO: here we omit the compution of L
      //TODO: here collect() is too much cost, find another method
      val lupdate = mat.rows.map(t => (t._1, t._2.toArray.apply(i) / c.value)).collect()
      val updateVec = Array.ofDim[Double](num)
      for (l <- lupdate) {
        updateVec.update(l._1.toInt, l._2)
      }
      val broadLV = mat.rows.context.broadcast(updateVec)
      val lresult = lowerMat.rows.mapPartitions(iter => {
        iter.map { t =>
          if (t._1.toInt >= i) {
            val vec = t._2.toArray
            vec.update(i, broadLV.value.apply(t._1.toInt))
            (t._1, Vectors.dense(vec))
          } else t
        }
      }, true)
      lowerMat = new DenseVecMatrix(lresult, numRows(), numCols())
      //cache the lower matrix to speed the computation
      val result = mat.rows.mapPartitions(iter => {
        iter.map(t => {
          if (t._1.toInt > i) {
            val vec = t._2.toArray
            //            val lupdate = vec.apply(i) / c.value
            val mfactor = -vec.apply(i) / c.value
            for (k <- 0 until vec.length) {
              vec.update(k, vec.apply(k) + mfactor * broadVec.value.apply(k))
            }
            (t._1, Vectors.dense(vec))
          } else t
        })
      }, true)
      mat = new DenseVecMatrix(result, numRows(), numCols())
      //cache the matrix to speed the computation
      //      mat.rows.cache()
      if (i % 50 == 0) {
        println("process has finish: " + i)
        println(Calendar.getInstance().getTime)
      }
      if (i % 2000 == 0) {
        if (mat.rows.context.getCheckpointDir.isDefined)
          mat.rows.checkpoint()
      }
    }
    (lowerMat, mat)
  }

  /**
   * This is an experimental implementation of block cholesky decomposition. The method is still in progress.
   * Cholesky decompose this DenseVecMatrix to generate a lower triangular matrix L
   * where A = L * L.transpose
   *
   * @param mode in which manner the computation should take place, local or distributed
   * @return denseVecMatrix L where A = L * L.transpose
   */
  def blockCholeskyDecompose(mode: String = "auto"): (DenseVecMatrix) = {
    def getFactor(n: Long, m: Long): Long = {
      if (n % m == 0) m
      else getFactor(n, m - 1)
    }

    require(numRows == numCols,
      s"currently we only support square matrix: ${numRows} vs ${numCols}")

    object CholeskyMode extends Enumeration {
      val LocalBreeze, DistSpark = Value
    }
    val computeMode = mode match {
      case "auto" => if (numRows > 6000L) {
        CholeskyMode.DistSpark
      } else {
        CholeskyMode.LocalBreeze
      }
      case "breeze" => CholeskyMode.LocalBreeze
      case "dist" => CholeskyMode.DistSpark
      case _ => throw new IllegalArgumentException(s"Do not support mode $mode.")
    }

    val lower: DenseVecMatrix = computeMode match {
      case CholeskyMode.LocalBreeze => {
        printf("in local mode, appear at most once")
        val brz = toBreeze()
        val l = brzCholesky(brz).t
        val lBro = rows.sparkContext.broadcast(l)
        val lDense = rows.mapPartitions(iter => {
          iter.map(t =>
            (t._1, Vectors.dense(lBro.value(::, t._1.toInt).toArray)))
        })
        new DenseVecMatrix(lDense, numRows, numRows)
      }


      case CholeskyMode.DistSpark => {
        //val numSubRows = getFactor(numRows, Math.sqrt(numRows).toLong).toInt
        val numSubRows = 1000
        val numSubRows2 = (numRows - numSubRows).toInt

        val subMatrix11 = getSubMatrix(0, numSubRows - 1, 0, numSubRows.toInt - 1)
        val subMatrix21 = getSubMatrix(numSubRows.toInt, numRows - 1, 0, numSubRows.toInt - 1)
        val subMatrix22 = getSubMatrix(numSubRows.toInt, numRows - 1, numSubRows.toInt, numCols.toInt - 1)


        val brzlMatrix11 = brzCholesky(subMatrix11.toBreeze()).t
        val lTranspose = subMatrix21.rows.context.broadcast(brzlMatrix11)
        val lMatrix21Rdd = subMatrix21
          .rows.mapPartitions(iter => {
          iter.map { t =>
            val array = t._2.toArray.clone()
            for (j <- 0 until numSubRows) {
              for (k <- 0 until j) {
                array(j) = array(j) - lTranspose.value.apply(j, k) * array(k)
              }
              array(j) = array(j) / lTranspose.value.apply(j, j)
            }
            (t._1, Vectors.dense(array))
          }
        })
        val lMatrix21 = new DenseVecMatrix(lMatrix21Rdd, numSubRows2, numSubRows)

        val lDense = subMatrix11.rows.mapPartitions(iter => {
          iter.map(t =>
            (t._1, Vectors.dense(lTranspose.value(::, t._1.toInt).toArray)))
        })
        val lMatrix11 = new DenseVecMatrix(lDense, numRows, numRows)

        val sc = rows.context
        val cores = if (!sc.getConf.getOption("spark.default.parallelism").isEmpty) {
          sc.getConf.get("spark.default.parallelism").toInt
        } else {
          sc.defaultMinPartitions
        }

        val lMatrix22 = subMatrix22
          .subtract(lMatrix21.multiply(lMatrix21.transpose(), cores)).blockCholeskyDecompose("auto")

        val lUpper = lMatrix11.rows
          .map(t => (t._1, Vectors.fromBreeze(BDV.vertcat(t._2.toBreeze.asInstanceOf[BDV[Double]], BDV.zeros[Double](numSubRows2)))))

        val lLower = lMatrix21.rows
          .join(lMatrix22.rows)
          .map { t =>
          val array1 = t._2._1.toArray
          val array2 = t._2._2.toArray
          (t._1 + numSubRows, Vectors.dense(array1 ++ array2))
        }
        val LMatrix = new DenseVecMatrix(lUpper.union(lLower), numRows(), numRows())
        LMatrix
      }
    }
    lower
  }

  /**
   * This function is still in progress.
   * get the result of cholesky decomposition of this DenseVecMatrix
   *
   * @return matrix A, where A * A' = Matrix
   */
  def choleskyDecompose(mode: String = "auto"): DenseVecMatrix = {
    val iterations = numRows
    require(iterations == numCols,
      s"currently we only support square matrix: ${iterations} vs ${numCols}")
    if (!rows.context.getCheckpointDir.isDefined) {
      println("Waning, checkpointdir is not set! We suggest you set it before running luDecopose")
    }
    // object LUmode extends Enumeration {
    // val LocalBreeze, DistSpark = Value
    // }
    // val computeMode = mode match {
    // case "auto" => if ( iterations > 10000L){
    // LUmode.DistSpark
    // }else {
    // LUmode.LocalBreeze
    // }
    // case "breeze" => LUmode.LocalBreeze
    // case "dist" => LUmode.DistSpark
    // case _ => throw new IllegalArgumentException(s"Do not support mode $mode.")
    // }
    //
    // val (lower: IndexMatrix, upper: IndexMatrix) = computeMode match {
    // case LUmode.LocalBreeze =>
    // val temp = bLU(toBreeze())
    // Matrices.fromBreeze(breeze.linalg.lowerTriangular(temp._1))
    // }
    //
    /**copy construct a DenseVecMatrix to maintain the original matrix **/
    var mat = new DenseVecMatrix(rows.map(t => {
      val array = Array.ofDim[Double](numCols().toInt)
      val v = t._2.toArray
      for (k <- 0 until v.length) {
        array(k) = v.apply(k)
      }
      (t._1, Vectors.dense(array))
    }))

    val num = iterations.toInt
    var lowerMat = MTUtils.zerosDenVecMatrix(rows.context, numRows(), numCols().toInt)
    for (i <- 0 until num) {
      val rowVector = mat.rows.filter(t => t._1.toInt == i).map(t => t._2).first()
      val colVector = mat.rows.map(t => t._2.toArray.apply(i)).collect()
      val c = mat.rows.context.broadcast(rowVector.apply(i))
      val broadRowVec = mat.rows.context.broadcast(rowVector)
      val broadColVec = mat.rows.context.broadcast(colVector)
      val result = mat.rows.mapPartitions(iter => {
        iter.map(t =>
          if (t._1.toInt >= i) {
            val vec = t._2.toArray
            if (t._1.toInt == i) {
              for (k <- i until vec.length)
                vec.update(k, vec(k) / Math.sqrt(c.value))
            } else {
              vec.update(i, vec(i) / Math.sqrt(c.value))
              for (k <- i + 1 until vec.length)
                vec.update(k, vec(k) - broadColVec.value.apply(t._1.toInt) * broadRowVec.value.apply(k) / c.value)
            }
            (t._1, Vectors.dense(vec))
          } else t)
      }, true)
      mat = new DenseVecMatrix(result, numRows(), numCols())
    }
    mat
  }

  def inverse(): DenseVecMatrix = {
    inverse("auto")
  }

  /**
   * get the inverse of this DenseVecMatrix
   *
   * @return the inverse matrix
   */
  def blockInverse(): DenseVecMatrix = {
    val (l, u, p) = blockLUDecompose("auto")
    val lInverse = l.inverseTriangular("auto", "lower")
    val uInverse = u.inverseTriangular("auto", "upper")
    val sc = rows.context

    val cores = if (!sc.getConf.getOption("spark.default.parallelism").isEmpty) {
      sc.getConf.get("spark.default.parallelism").toInt
    } else {
      sc.defaultMinPartitions
    }
    val tmp = uInverse.multiply(lInverse, cores).toDenseVecMatrix()
    val bp = tmp.rows.context.broadcast(p)
    val pivotRdd = tmp.rows.mapPartitions (iter => {
      iter.map{ t =>
        val array = Array.ofDim[Double](numRows().toInt)
        array(bp.value(t._1.toInt)) = 1
        (t._1, Vectors.dense(array))
      }
    })
    val pivot = new DenseVecMatrix(pivotRdd, numRows(), numCols())
    val result = tmp.multiply(pivot, cores).toDenseVecMatrix()
    result
  }

  /**
   * This function still works in progress.
   * get the inverse of this DenseVecMatrix
   *
   * @return the inverse of the square matrix
   */
  def inverse(mode: String = "auto"): DenseVecMatrix = {
    val iterations = numRows()
    require(iterations == numCols(),
      s"currently we only support square matrix: ${iterations} vs ${numCols}")
    if (!rows.context.getCheckpointDir.isDefined) {
      println("Waning, checkpointdir is not set! We suggest you set it before running luDecopose")
    }
    /**copy construct a DenseVecMatrix to maintain the original matrix **/
    var matr = new DenseVecMatrix(rows.map(t => {
      val array = Array.ofDim[Double](numCols().toInt)
      val v = t._2.toArray
      for (k <- 0 until v.length) {
        array(k) = v.apply(k)
      }
      (t._1, Vectors.dense(array))
    }))

    val num = iterations.toInt
    val permutation = Array.range(0, num)

    for (i <- 0 until num) {

      val updateVec = matr.rows.map(t => (t._1, t._2.toArray.apply(i))).collect()

      val ideal = updateVec.filter(_._1.toInt >= i).maxBy(t => t._2.abs)

      if (math.abs(ideal._2) < 1.0e-20) {
        throw new IllegalArgumentException("The matrix must be non-singular")
      } else if (i != ideal._1.toInt) {
        //need to swap the rows with row number ideal_.1 and i
        val currentRow = matr.rows.filter(t => t._1.toInt == i).first()
        val idealRow = matr.rows.filter(t => t._1 == ideal._1).first()
        val result = matr.rows.map(t =>
          if (t._1.toInt == i)
            (t._1, idealRow._2)
          else if (t._1.toInt == ideal._1)
            (t._1, currentRow._2)
          else
            t)
        matr = new DenseVecMatrix(result, numRows(), numCols())
        //updateVec = matr.rows.map(t => (t._1, t._2.toArray.apply(i))).collect()
        val tmp = updateVec(i)._2
        updateVec.update(i, (updateVec(i)._1, idealRow._2.apply(i)))
        updateVec.update(idealRow._1.toInt, (updateVec(i)._1, tmp))

        val tmp2 = permutation(i)
        permutation.update(i, permutation(idealRow._1.toInt))
        permutation.update(idealRow._1.toInt, tmp2)
        /*
        updateVec = updateVec.map(t =>
          if (t._1 == i)
            (t._1, idealRow._2.apply(i))
          else if (t._1 == idealRow._1)
            (t._1, tmp)
          else t)
        *
        *
        */
      }

      val vector = matr.rows.filter(_._1.toInt == i).map(t => t._2).collect().apply(0).toArray.clone
      val c = matr.rows.context.broadcast(vector.apply(i))

      //  vector.foreach(t => (t/c.value))
      for (i <- 0 until vector.length)
        vector.update(i, vector(i) / c.value)
      val broadRow = matr.rows.context.broadcast(vector)

      val broadCol = matr.rows.context.broadcast(updateVec.map(t => t._2))

      val result = matr.rows.mapPartitions(iter => {
        iter.map { t =>
          val vec = t._2.toArray.clone()
          if (t._1.toInt == i) {
            for (k <- 0 until vec.length)
              if (k == i) {
                vec.update(k, broadRow.value.apply(k) / c.value)
              } else {
                vec.update(k, broadRow.value.apply(k))
              }
          } else {
            for (k <- 0 until vec.length if k != i) {
              vec.update(k, vec(k) - broadCol.value.apply(t._1.toInt) * broadRow.value.apply(k))
            }
          }
          if (t._1.toInt != i)
            vec.update(i, vec(i) / (-c.value))
          (t._1, Vectors.dense(vec))
        }
      }, true)
      matr = new DenseVecMatrix(result, numRows(), numCols())
      //cache the matrix to speed the computation
      //matr.rows.cache()
      if (i % 50 == 0) {
        println("process has finish: " + i)
        println(Calendar.getInstance().getTime)
      }
      if (i % 2000 == 0) {
        if (matr.rows.context.getCheckpointDir.isDefined)
          matr.rows.checkpoint()
      }
    }
    
    val permuMatrix = new DenseVecMatrix(rows.map(t => {
      val array = Array.fill[Double](numCols.toInt)(0)
      array(permutation(t._1.toInt)) = 1;
      (t._1, Vectors.dense(array))
    }))

    matr.multiplyHama(permuMatrix, 2).toDenseVecMatrix()
  }

  /**
   * get the inverse of the triangular matrix
   * @param mode  in which manner should the inverse be calculated, locally or distributed
   * @param form  the type of the triangular matrix, "lower" as lower triangular, "upper" as upper triangular
   * @return
   */
  private[marlin] def inverseTriangular(mode: String = "auto", form: String): DenseVecMatrix = {
    def getFactor(n: Long, m: Long): Long = {
      if (n % m == 0) m
      else getFactor(n, m - 1)
    }
    require(numRows == numCols,
      s"currently we only support square matrix: ${numRows} vs ${numCols}")

    object LUmode extends Enumeration {
      val LocalBreeze, DistSpark = Value
    }
    val computeMode = mode match {
      case "auto" => if (numRows > 6000L) {
        LUmode.DistSpark
      } else {
        LUmode.LocalBreeze
      }
      case "breeze" => LUmode.LocalBreeze
      case "dist" => LUmode.DistSpark
      case _ => throw new IllegalArgumentException(s"Do not support mode $mode.")
    }

    val inverse: DenseVecMatrix = computeMode match {
      case LUmode.LocalBreeze => {
        val brz = toBreeze()
        val inv = brzInv(brz).t
        val invBro = rows.sparkContext.broadcast(inv)
        val invRdd = rows.mapPartitions(iter => {
          iter.map(t =>
            (t._1, Vectors.dense(invBro.value(::, t._1.toInt).toArray)))
        })
        (new DenseVecMatrix(invRdd, numRows, numRows))
      }
      case LUmode.DistSpark => {
        //val numSubRows = getFactor(numRows, Math.sqrt(numRows).toLong).toInt
        val numSubRows2 = 1000
        val numSubRows = (numRows - numSubRows2).toInt

        val subMatrix11 = getSubMatrix(0, numSubRows - 1, 0, numSubRows.toInt - 1)
        val subMatrix = if (form == "lower") {
          getSubMatrix(numSubRows.toInt, numRows - 1, 0, numSubRows.toInt - 1)
        }
        else {
          getSubMatrix(0, numSubRows - 1, numSubRows.toInt, numCols.toInt - 1)
        }
        val subMatrix22 = getSubMatrix(numSubRows.toInt, numRows - 1, numSubRows.toInt, numCols.toInt - 1)

        val sc = rows.context
        val cores = if (!sc.getConf.getOption("spark.default.parallelism").isEmpty) {
          sc.getConf.get("spark.default.parallelism").toInt
        } else {
          sc.defaultMinPartitions
        }

        val invMatrix11 = subMatrix11.inverseTriangular("auto", form)

        val brzInvMatrix22 = rows.sparkContext.broadcast(brzInv(subMatrix22.toBreeze()).t)

        val inv22Rdd = subMatrix22.rows.mapPartitions(iter => {
          iter.map(t =>
            (t._1, Vectors.dense(brzInvMatrix22.value(::, t._1.toInt).toArray)))
        })
        val invMatrix22 = (new DenseVecMatrix(inv22Rdd, numSubRows2, numSubRows2))
        /**
        val blkInvMatrix22 = new BlockMatrix(
          rows.context.parallelize(Array[(BlockID, BDM[Double])]((new BlockID(0, 0), brzInvMatrix22.value.t))),
          numSubRows2,
          numSubRows2,
          1,
          1)


        val tmp = subMatrix21.multiply(invMatrix11, cores).subtractBy(0)
        val parallelism = math.min(8 * cores, tmp.numRows() / 2).toInt

//        val invMatrix21 = blkInvMatrix22
 //         .multiplyBroadcast(tmp, parallelism, (1, 1, parallelism), "broadcastA" ).toDenseVecMatrix()
          */

        val invMatrix = if (form == "lower") {
          invMatrix22.multiply(subMatrix.multiply(invMatrix11, cores).subtractBy(0), cores).toDenseVecMatrix()
        }else {
          invMatrix11.multiply(subMatrix.multiply(invMatrix22, cores).subtractBy(0), cores).toDenseVecMatrix()
        }

        val rst = if (form == "lower") {
          val u = invMatrix11.rows
            .map(t => (t._1, Vectors.fromBreeze(BDV.vertcat(t._2.toBreeze.asInstanceOf[BDV[Double]], BDV.zeros[Double](numSubRows2)))))
          val l = invMatrix.rows
            .join(invMatrix22.rows)
            .map { t =>
            val array1 = t._2._1.toArray
            val array2 = t._2._2.toArray
            (t._1 + numSubRows, Vectors.dense(array1 ++ array2))
          }
          (u, l)
        }else {
          val u = invMatrix11.rows
            .join(invMatrix.rows)
            .map { t =>
            val array1 = t._2._1.toArray
            val array2 = t._2._2.toArray
            (t._1, Vectors.dense(array1 ++ array2))
          }
          val l = invMatrix22.rows
            .map(t => (t._1 + numSubRows, Vectors.fromBreeze(BDV.vertcat(BDV.zeros[Double](numSubRows), t._2.toBreeze.asInstanceOf[BDV[Double]]))))
          (u, l)
        }
        val mat = new DenseVecMatrix(rst._1.union(rst._2), numRows(), numRows())
        mat
      }
    }
    inverse
  }

  /**
   * This matrix add another DistributedMatrix
   *
   * @param other another matrix in DenseVecMatrix format
   */
  final def add(other: DistributedMatrix): DenseVecMatrix = {
    other match {
      case that: DenseVecMatrix => {
        require(numRows() == that.numRows(), s"Dimension mismatch: ${numRows()} vs ${that.numRows()}")
        require(numCols() == that.numCols, s"Dimension mismatch: ${numCols()} vs ${that.numCols()}")

        val result = rows.join(that.rows).mapPartitions(iter => {
          iter.map(t =>
            (t._1, Vectors.fromBreeze((t._2._1.toBreeze + t._2._2.toBreeze).asInstanceOf[BDV[Double]])))
        }, true)
        new DenseVecMatrix(result, numRows(), numCols())
      }
      case that: BlockMatrix => {
        add(that.toDenseVecMatrix())
      }
      case that: DistributedMatrix => {
        throw new IllegalArgumentException("Do not support this type " + that.getClass + "for add operation")
      }
    }

  }

  /**
   * This matrix minus another DistributedMatrix
   *
   * @param other another matrix in DenseVecMatrix format
   */
  final def subtract(other: DistributedMatrix): DenseVecMatrix = {
    other match {
      case that: DenseVecMatrix => {
        require(numRows() == that.numRows(), s"Row dimension mismatch: ${numRows()} vs ${other.numRows()}")
        require(numCols == that.numCols, s"Column dimension mismatch: ${numCols()} vs ${other.numCols()}")

        val result = rows.join(that.rows).mapPartitions(iter => {
          iter.map(t =>
            (t._1, Vectors.dense(t._2._1.toArray.zip(t._2._2.toArray).map(x => x._1 - x._2))))
        }, true)
        new DenseVecMatrix(result, numRows(), numCols())
      }
      case that: BlockMatrix => {
        subtract(that.toDenseVecMatrix())
      }
    }

  }

  /**
   * Element in this matrix element-wise add another scalar
   *
   * @param b the number to be element-wise added
   */
  final def add(b: Double): DenseVecMatrix = {
    val result = rows.mapPartitions(iter => { iter.map(t => (t._1, Vectors.dense(t._2.toArray.map(_ + b)))) }, true)
    new DenseVecMatrix(result, numRows(), numCols())
  }

  /**
   * Element in this matrix element-wise minus another scalar
   *
   * @param b a number to be element-wise subtracted
   */
  final def subtract(b: Double): DenseVecMatrix = {
    val result = rows.mapPartitions(iter => { iter.map(t => (t._1, Vectors.dense(t._2.toArray.map(_ - b)))) }, true)
    new DenseVecMatrix(result, numRows(), numCols())
  }

  /**
   * Element in this matrix element-wise minus by another scalar
   *
   * @param b a number in the format of double
   */
  final def subtractBy(b: Double): DenseVecMatrix = {
    val result = rows.mapPartitions(iter => { iter.map(t => (t._1, Vectors.dense(t._2.toArray.map(b - _)))) }, true)
    new DenseVecMatrix(result, numRows(), numCols())
  }

  /**
   * Element in this matrix element-wise multiply another scalar
   *
   * @param b a number in the format of double
   */
  final def multiply(b: Double): DenseVecMatrix = {
    val result = rows.mapPartitions(iter => { iter.map(t => (t._1, Vectors.dense(t._2.toArray.map(_ * b)))) }, true)
    new DenseVecMatrix(result, numRows(), numCols())
  }

  /**
   * Element in this matrix element-wise divide another scalar
   *
   * @param b a number in the format of double
   * @return result in DenseVecMatrix type
   */
  final def divide(b: Double): DenseVecMatrix = {
    val result = rows.mapPartitions(iter => { iter.map(t => (t._1, Vectors.dense(t._2.toArray.map(_ / b)))) }, true)
    new DenseVecMatrix(result, numRows(), numCols())
  }

  /**
   * Element in this matrix element-wise divided by another scalar
   *
   * @param b a number in the format of double
   */
  final def divideBy(b: Double): DenseVecMatrix = {
    val result = rows.mapPartitions(iter => { iter.map(t => (t._1, Vectors.dense(t._2.toArray.map(b / _)))) }, true)
    new DenseVecMatrix(result, numRows(), numCols())
  }

  /**
   * Sum all the elements in matrix ,note the Double.MaxValue is 1.7976931348623157E308
   *
   */
  def sum(): Double = {
    rows.mapPartitions(iter =>
      iter.map(t => t._2.toArray.sum), true).reduce(_ + _)
  }

  /**
   * Matrix-matrix dot product, the two input matrices must have the same row and column dimension
   * @param other the matrix to be dot product
   * @return
   */
  def dotProduct(other: DistributedMatrix): DistributedMatrix = {
    require(numRows() == other.numRows(), s"row dimension mismatch ${numRows()} vs ${other.numRows()}")
    require(numCols() == other.numCols(), s"column dimension mismatch ${numCols()} vs ${other.numCols()}")
    other match {
      case that: DenseVecMatrix => {
        val result = rows.join(that.rows).mapPartitions(iter => {
          iter.map(t => {
            val array = t._2._1.toArray.zip(t._2._2.toArray).map(x => x._1 * x._2)
            (t._1, Vectors.dense(array))
          })
        }, true)
        new DenseVecMatrix(result, numRows(), numCols())
      }
      case that: BlockMatrix => {
        dotProduct(that.toDenseVecMatrix())
      }
    }
  }

  /**
   * Get sub matrix according to the given range of rows
   *
   * @param startRow the start row of the subMatrix, this row is included
   * @param endRow the end row of the subMatrix, this row is included
   */
  final def sliceByRow(startRow: Long, endRow: Long): DenseVecMatrix = {
    require((startRow >= 0 && endRow <= numRows() && startRow <= endRow),
      s"start row or end row mismatch the matrix num of rows")

    new DenseVecMatrix(rows.filter(t => (t._1 >= startRow && t._1 <= endRow)).map(t => (t._1 - startRow, t._2)), endRow - startRow + 1, numCols())
  }

  /**
   * get sub matrix according to the given range of column
   *
   * @param startCol the start column of the subMatrix, this column is included
   * @param endCol the end column of the subMatrix, this column is included
   */
  final def sliceByColumn(startCol: Int, endCol: Int): DenseVecMatrix = {
    require((startCol >= 0 && endCol <= numCols() && startCol <= endCol),
      s"start column or end column mismatch the matrix num of columns")

    new DenseVecMatrix(rows.map(t => (t._1, Vectors.dense(t._2.toArray.slice(startCol, endCol + 1)))), numRows(), endCol - startCol + 1)
  }

  /**
   * get sub matrix according to the given range of column
   *
   * @param startRow the start row of the subMatrix, this row is included
   * @param endRow the end row of the subMatrix, this row is included
   * @param startCol the start column of the subMatrix, this column is included
   * @param endCol the end column of the subMatrix, this column is included
   */
  final def getSubMatrix(startRow: Long, endRow: Long, startCol: Int, endCol: Int): DenseVecMatrix = {
    require((startRow >= 0 && endRow <= numRows()), s"start row or end row dismatch the matrix num of rows")
    require((startCol >= 0 && endCol <= numCols()),
      s"start column or end column dismatch the matrix num of columns")

    new DenseVecMatrix(rows
      .filter(t => (t._1 >= startRow && t._1 <= endRow))
      .map(t => (t._1 - startRow, Vectors.dense(t._2.toArray.slice(startCol, endCol + 1)))), endRow - startRow + 1, endCol - startCol + 1)
  }

  /**
   * compute the norm of this matrix
   *
   * @param mode the same with Matlab operations,
   *             `1` means get the 1-norm, the largest column sum of matrix
   *             `2` means get the largest singular value, the default mode --- need to do
   *             `inf` means the infinity norm, the largest row sum of matrix
   *             `fro` means the Frobenius-norm of matrix -- need to do
   */
  final def norm(mode: String): Double = {
    mode match {
      case "1" => {
        rows.mapPartitions(iter => {
          val columnAcc = new ParArray[Double](numCols().toInt)
          iter.map(t => {
            val arr = t._2.toArray
            for (i <- 0 until arr.length) {
              columnAcc(i) += math.abs(arr(i))
            }
          })
          columnAcc.toIterator
        }).max()
      }
      //      case "2" => {
      //
      //      }
      case "inf" => {
        rows.map(t => t._2.toArray.reduce(_ + _)).max()
      }
      //      case "for" => {
      //
      //      }
    }
  }

  /**
   * Save the result to the HDFS or local file system
   *
   * @param path the path to store the DenseVecMatrix in HDFS or local file system
   */
  def saveToFileSystem(path: String) {

    rows.map(t => (NullWritable.get(), new Text(t._1 + ":" + t._2.toString)))
      .saveAsHadoopFile[TextOutputFormat[NullWritable, Text]](path)
  }

  /**
   * Save the result to HDFS with a description file name `_description`, the description file content is like:
   * MatrixName[TAB]name (if not available, use N/A)
   * MatrixSize[TAB]row column
   *
   * @param path
   */
  def saveWithDescription(path: String): Unit = {
    rows.map(t => (NullWritable.get(), new Text(t._1 + ":" + t._2.toString)))
      .saveAsHadoopFile[TextOutputFormat[NullWritable, Text]](path)
    val conf = new Configuration()
    val hdfs = FileSystem.get(conf)
    val out = hdfs.create(new Path(path + "/_description"))
    val info = "MatrixName\tN/A\nMatrixSize\t" + numRows() + " " + numCols()
    out.write(info.getBytes())
    out.close()
  }

  /**
   * A simple wraper of saving the DenseVecMatrix to SequenceFile
   *
   * @param path matrix file path
   */
  def saveSequenceFile(path: String) {
    rows.saveAsSequenceFile(path)
  }

  /**
   * transform the DenseVecMatrix to BlockMatrix
   *
   * @param numByRow num of subMatrix along the row side set by user,
   *                 the actual num of subMatrix along the row side is `blksByRow`
   * @param numByCol num of subMatrix along the column side set by user,
   *                 the actual num of subMatrix along the col side is `blksByCol`
   * @return the transformed matrix in BlockMatrix type
   */

  def toBlockMatrix(numByRow: Int, numByCol: Int): BlockMatrix = {

    val mRows = numRows().toInt
    val mColumns = numCols().toInt
    val mBlockRowSize = math.ceil(mRows.toDouble / numByRow.toDouble).toInt
    val mBlockColSize = math.ceil(mColumns.toDouble / numByCol.toDouble).toInt
    val blksByRow = math.ceil(mRows.toDouble / mBlockRowSize).toInt
    val blksByCol = math.ceil(mColumns.toDouble / mBlockColSize).toInt

    if (blksByCol == 1) {
      val result = rows.mapPartitions(iter => {
        iter.map(t => {
          (t._1.toInt / mBlockRowSize, t)
        })
      }).groupByKey().mapPartitions(iter => {
        iter.map(t => {
          val blockRow = t._1
          val rowLen = if ((blockRow + 1) * mBlockRowSize > numRows()) {
            numRows().toInt - blockRow * mBlockRowSize
          } else { mBlockRowSize }
          val iterator = t._2.iterator
          val mat = BDM.zeros[Double](rowLen, mBlockColSize)
          while (iterator.hasNext) {
            val (index, vec) = iterator.next()
            vec.toArray.zipWithIndex.map(x => {
              mat.update(index.toInt - blockRow * mBlockRowSize, x._2, x._1)
            })
          }
          (new BlockID(t._1, 0), mat)
        })
      })
      new BlockMatrix(result, numRows(), numCols(), blksByRow, blksByCol)
    } else {
      val result = rows.mapPartitions(iter => {
        iter.flatMap(t => {
          var startColumn = 0
          var endColumn = 0
          var arrayBuf = new ArrayBuffer[(BlockID, (Long, Vector))]

          val elems = t._2.toArray
          var i = 0
          while (endColumn < (mColumns - 1)) {
            startColumn = i * mBlockColSize
            endColumn = startColumn + mBlockColSize - 1
            if (endColumn >= mColumns) {
              endColumn = mColumns - 1
            }

            val vector = new Array[Double](endColumn - startColumn + 1)
            for (j <- startColumn to endColumn) {
              vector(j - startColumn) = elems(j)
            }

            arrayBuf += ((new BlockID(t._1.toInt / mBlockRowSize, i), (t._1, Vectors.dense(vector))))
            i += 1
          }
          arrayBuf
        })
      })
        .groupByKey()
        .mapPartitions(a => {
          a.map(
            input => {
              val colBase = input._1.column * mBlockColSize
              val rowBase = input._1.row * mBlockRowSize

              //the block's size: rows & columns
              var smRows = mBlockRowSize
              if ((rowBase + mBlockRowSize - 1) >= mRows) {
                smRows = mRows - rowBase
              }

              var smCols = mBlockColSize
              if ((colBase + mBlockColSize - 1) >= mColumns) {
                smCols = mColumns - colBase
              }

              val itr = input._2.iterator
              //to generate the local matrix, be careful, the array is column major
              val array = Array.ofDim[Double](smRows * smCols)

              while (itr.hasNext) {
                val vec = itr.next()
                if (vec._2.size != smCols) {
                  Logger.getLogger(getClass).
                    log(Level.ERROR, "vectors:  " + input._2 + "Block Column Size mismatch")
                  throw new IOException("Block Column Size mismatch")
                }

                val rowOffset = vec._1.toInt - rowBase
                if (rowOffset >= smRows || rowOffset < 0) {
                  Logger.getLogger(getClass).log(Level.ERROR, "Block Row Size mismatch")
                  throw new IOException("Block Row Size mismatch")
                }

                val tmp = vec._2.toArray
                for (i <- 0 until tmp.length) {
                  array(i * smRows + rowOffset) = tmp(i)
                }
              }

              val subMatrix = new BDM(smRows, smCols, array)
              (input._1, subMatrix)
            })
        }, true)

      new BlockMatrix(result, numRows(), numCols(), blksByRow, blksByCol)
    }
  }

  /**
   * transform the DenseVecMatrix to SparseVecMatrix
   */
  def toSparseVecMatrix(): SparseVecMatrix = {
    val result = rows.mapPartitions(iter => {
      iter.map(t => {
        val array = t._2.toArray
        val indices = new ArrayBuffer[Int]()
        val values = new ArrayBuffer[Double]()
        for (i <- 0 until array.length) {
          if (array(i) != 0) {
            indices += i
            values += array(i)
          }
        }
        if (indices.size >= 0) {
          (t._1, new SparseVector(indices.size, indices.toArray, values.toArray))
        } else { throw new IllegalArgumentException("indices size is empty") }
      })
    })
    new SparseVecMatrix(result, numRows(), numCols())
  }

  /**
   * Print the matrix out
   */
  def print() {
    if (numRows() > 20) {
      rows.take(20).foreach(t => println("index: " + t._1 + ", vector: " + t._2.print(8)))
      println("there are " + numRows() + " rows total...")
    } else {
      rows.collect().foreach(t => println("index: " + t._1 + ", vector: " + t._2.print(8)))
    }
  }

  /**
   * Print the whole matrix out
   */
  def printAll() {
    rows.collect().foreach(t => println("index: " + t._1 + ", vector: " + t._2.print()))
  }

  /**
   * A transpose view of this matrix
   * @return
   */
  def transpose(): BlockMatrix = {
    require(numRows() < Int.MaxValue, s"the row length of matrix is too large to transpose")
    val sc = rows.context
    val blkByRow = if (!sc.getConf.getOption("spark.default.parallelism").isEmpty) {
      sc.getConf.get("spark.default.parallelism").toInt
    } else {
      sc.defaultMinPartitions
    }
    toBlockMatrix(math.min(blkByRow, numRows().toInt / 2), 1).transpose()
  }

  /**
   * Multiplies the Gramian matrix `A^T A` by a dense vector on the right without computing `A^T A`.
   *
   * @param v a dense vector whose length must match the number of columns of this matrix
   * @return a dense vector representing the product
   */
  private[marlin] def multiplyGramianMatrixBy(v: BDV[Double]): BDV[Double] = {
    val n = numCols().toInt
    val vbr = rows.context.broadcast(v)
    rows.aggregate(BDV.zeros[Double](n))(
      seqOp = (U, r) => {
        val rBrz = r._2.toBreeze
        val a = rBrz.dot(vbr.value)
        rBrz match {
          // use specialized axpy for better performance
          case _: BDV[_] => brzAxpy(a, rBrz.asInstanceOf[BDV[Double]], U)
          case _ => throw new UnsupportedOperationException(
            s"Do not support vector operation from type ${rBrz.getClass.getName}.")
        }
        U
      }, combOp = (U1, U2) => U1 += U2)
  }

  /**
   * Computes the Gramian matrix `A^T A`.
   */
  private[marlin] def computeGramianMatrix(): Matrix = {
    def checkNumColumns(cols: Int): Unit = {
      if (cols > 65535) {
        throw new IllegalArgumentException(s"Argument with more than 65535 cols: $cols")
      }
      if (cols > 10000) {
        val mem = cols * cols * 8
        logWarning(s"$cols columns will require at least $mem bytes of memory!")
      }
    }
    val n = numCols().toInt
    checkNumColumns(n)
    // Computes n*(n+1)/2, avoiding overflow in the multiplication.
    // This succeeds when n <= 65535, which is checked above
    val nt: Int = if (n % 2 == 0) ((n / 2) * (n + 1)) else (n * ((n + 1) / 2))
    // Compute the upper triangular part of the gram matrix.
    val GU = rows.aggregate(new BDV[Double](new Array[Double](nt)))(
      seqOp = (U, v) => {
        DenseVecMatrix.dspr(1.0, v._2, U.data)
        U
      }, combOp = (U1, U2) => U1 += U2)
    DenseVecMatrix.triuToFull(n, GU.data)
  }

  /**
   * Computes singular value decomposition of this matrix. Denote this matrix by A (m x n). This
   * will compute matrices U, S, V such that A ~= U * S * V', where S contains the leading k
   * singular values, U and V contain the corresponding singular vectors.
   *
   * At most k largest non-zero singular values and associated vectors are returned. If there are k
   * such values, then the dimensions of the return will be:
   * - U is a RowMatrix of size m x k that satisfies U' * U = eye(k),
   * - s is a Vector of size k, holding the singular values in descending order,
   * - V is a Matrix of size n x k that satisfies V' * V = eye(k).
   *
   * We assume n is smaller than m. The singular values and the right singular vectors are derived
   * from the eigenvalues and the eigenvectors of the Gramian matrix A' * A. U, the matrix
   * storing the right singular vectors, is computed via matrix multiplication as
   * U = A * (V * S^-1^), if requested by user. The actual method to use is determined
   * automatically based on the cost:
   * - If n is small (n &lt; 100) or k is large compared with n (k > n / 2), we compute the Gramian
   * matrix first and then compute its top eigenvalues and eigenvectors locally on the driver.
   * This requires a single pass with O(n^2^) storage on each executor and on the driver, and
   * O(n^2^ k) time on the driver.
   * - Otherwise, we compute (A' * A) * v in a distributive way and send it to ARPACK's DSAUPD to
   * compute (A' * A)'s top eigenvalues and eigenvectors on the driver node. This requires O(k)
   * passes, O(n) storage on each executor, and O(n k) storage on the driver.
   *
   * Several internal parameters are set to default values. The reciprocal condition number rCond
   * is set to 1e-9. All singular values smaller than rCond * sigma(0) are treated as zeros, where
   * sigma(0) is the largest singular value. The maximum number of Arnoldi update iterations for
   * ARPACK is set to 300 or k * 3, whichever is larger. The numerical tolerance for ARPACK's
   * eigen-decomposition is set to 1e-10.
   *
   * @note The conditions that decide which method to use internally and the default parameters are
   * subject to change.
   *
   * @param k number of leading singular values to keep (0 &lt; k &lt;= n).
   * It might return less than k if
   * there are numerically zero singular values or there are not enough Ritz values
   * converged before the maximum number of Arnoldi update iterations is reached (in case
   * that matrix A is ill-conditioned).
   * @param computeU whether to compute U
   * @param rCond the reciprocal condition number. All singular values smaller than rCond * sigma(0)
   * are treated as zero, where sigma(0) is the largest singular value.
   * @return SingularValueDecomposition(U, s, V). U = null if computeU = false.
   */
  def computeSVD(
    k: Int,
    computeU: Boolean = false,
    rCond: Double = 1e-9): (DenseVecMatrix, DenseVector, Matrix) = {
    // maximum number of Arnoldi update iterations for invoking ARPACK
    val maxIter = math.max(300, k * 3)
    // numerical tolerance for invoking ARPACK
    val tol = 1e-10

    computeSVD(k, computeU, rCond, maxIter, tol, "auto")
  }

  /**
   * The actual SVD implementation, visible for testing.
   *
   * @param k number of leading singular values to keep (0 &lt; k &lt;= n)
   * @param computeU whether to compute U
   * @param rCond the reciprocal condition number
   * @param maxIter max number of iterations (if ARPACK is used)
   * @param tol termination tolerance (if ARPACK is used)
   * @param mode computation mode (auto: determine automatically which mode to use,
   * local-svd: compute gram matrix and computes its full SVD locally,
   * local-eigs: compute gram matrix and computes its top eigenvalues locally,
   * dist-eigs: compute the top eigenvalues of the gram matrix distributively)
   * @return SingularValueDecomposition(U, s, V). U = null if computeU = false.
   */
  private[marlin] def computeSVD(
    k: Int,
    computeU: Boolean,
    rCond: Double,
    maxIter: Int,
    tol: Double,
    mode: String): (DenseVecMatrix, DenseVector, Matrix) = {
    val n = numCols().toInt
    require(k > 0 && k <= n, s"Request up to n singular values but got k=$k and n=$n.")
    object SVDMode extends Enumeration {
      val LocalARPACK, LocalLAPACK, DistARPACK = Value
    }
    val computeMode = mode match {
      case "auto" =>
        // TODO: The conditions below are not fully tested.
        if (n < 100 || k > n / 2) {
          // If n is small or k is large compared with n, we better compute the Gramian matrix first
          // and then compute its eigenvalues locally, instead of making multiple passes.
          if (k < n / 3) {
            SVDMode.LocalARPACK
          } else {
            SVDMode.LocalLAPACK
          }
        } else {
          // If k is small compared with n, we use ARPACK with distributed multiplication.
          SVDMode.DistARPACK
        }
      case "local-svd" => SVDMode.LocalLAPACK
      case "local-eigs" => SVDMode.LocalARPACK
      case "dist-eigs" => SVDMode.DistARPACK
      case _ => throw new IllegalArgumentException(s"Do not support mode $mode.")
    }
    // Compute the eigen-decomposition of A' * A.
    val (sigmaSquares: BDV[Double], u: BDM[Double]) = computeMode match {
      case SVDMode.LocalARPACK =>
        require(k < n, s"k must be smaller than n in local-eigs mode but got k=$k and n=$n.")
        val G = computeGramianMatrix().toBreeze.asInstanceOf[BDM[Double]]
        EigenValueDecomposition.symmetricEigs(v => G * v, n, k, tol, maxIter)
      case SVDMode.LocalLAPACK =>
        val G = computeGramianMatrix().toBreeze.asInstanceOf[BDM[Double]]
        val svdResult = brzSvd(G)
        (svdResult.S, svdResult.U)
      case SVDMode.DistARPACK =>
        if (rows.getStorageLevel == StorageLevel.NONE) {
          logWarning("The input data is not directly cached, which may hurt performance if its"
            + " parent RDDs are also uncached.")
        }
        require(k < n, s"k must be smaller than n in dist-eigs mode but got k=$k and n=$n.")
        EigenValueDecomposition.symmetricEigs(multiplyGramianMatrixBy, n, k, tol, maxIter)
    }
    val sigmas: BDV[Double] = brzSqrt(sigmaSquares)
    // Determine the effective rank.
    val sigma0 = sigmas(0)
    val threshold = rCond * sigma0
    var i = 0
    // sigmas might have a length smaller than k, if some Ritz values do not satisfy the convergence
    // criterion specified by tol after max number of iterations.
    // Thus use i < min(k, sigmas.length) instead of i < k.
    if (sigmas.length < k) {
      logWarning(s"Requested $k singular values but only found ${sigmas.length} converged.")
    }
    while (i < math.min(k, sigmas.length) && sigmas(i) >= threshold) {
      i += 1
    }
    val sk = i
    if (sk < k) {
      logWarning(s"Requested $k singular values but only found $sk nonzeros.")
    }
    // Warn at the end of the run as well, for increased visibility.
    if (computeMode == SVDMode.DistARPACK && rows.getStorageLevel == StorageLevel.NONE) {
      logWarning("The input data was not directly cached, which may hurt performance if its"
        + " parent RDDs are also uncached.")
    }
    val s = Vectors.dense(Arrays.copyOfRange(sigmas.data, 0, sk))

    val V = Matrices.dense(n, sk, Arrays.copyOfRange(u.data, 0, n * sk)).asInstanceOf[DenseMatrix]
    if (computeU) {
      // N = Vk * Sk^{-1}
      val N = new BDM[Double](n, sk, Arrays.copyOfRange(u.data, 0, n * sk))
      var i = 0
      var j = 0
      while (j < sk) {
        i = 0
        val sigma = sigmas(j)
        while (i < n) {
          N(i, j) /= sigma
          i += 1
        }
        j += 1
      }
      val U = this.multiply(Matrices.fromBreeze(N))
      (U, s, V)
    } else {
      (null, s, V)
    }
  }

  /**
   * Multiply this matrix by a local matrix on the right.
   *
   * @param B a local matrix whose number of rows must match the number of columns of this matrix
   * @return a [[edu.nju.pasalab.marlin.matrix.DenseVecMatrix]] representing the product,
   * which preserves partitioning
   */
  def multiply(B: Matrix): DenseVecMatrix = {
    val n = numCols().toInt
    require(n == B.numRows, s"Dimension mismatch: $n vs ${B.numRows}")
    require(B.isInstanceOf[DenseMatrix],
      s"Only support dense matrix at this time but found ${B.getClass.getName}.")
    val Bb = rows.context.broadcast(B)
    val AB = rows.mapPartitions({ iter =>
      val Bi = Bb.value.toBreeze.asInstanceOf[BDM[Double]]
      iter.map(v => (v._1, Vectors.fromBreeze(Bi.t * v._2.toBreeze.asInstanceOf[BDV[Double]])))
    }, preservesPartitioning = true)
    new DenseVecMatrix(AB, nRows, B.numCols)
  }

}

object DenseVecMatrix {
  /**
   * Adds alpha * x * x.t to a matrix in-place. This is the same as BLAS's DSPR.
   *
   * @param U the upper triangular part of the matrix packed in an array (column major)
   */
  private def dspr(alpha: Double, v: Vector, U: Array[Double]): Unit = {
    // TODO: Find a better home (breeze?) for this method.
    val n = v.size
    v match {
      case dv: DenseVector =>
        blas.dspr("U", n, alpha, dv.values, 1, U)
      case sv: SparseVector =>
        val indices = sv.indices
        val values = sv.values
        val nnz = indices.length
        var colStartIdx = 0
        var prevCol = 0
        var col = 0
        var j = 0
        var i = 0
        var av = 0.0
        while (j < nnz) {
          col = indices(j)
          // Skip empty columns.
          colStartIdx += (col - prevCol) * (col + prevCol + 1) / 2
          col = indices(j)
          av = alpha * values(j)
          i = 0
          while (i <= j) {
            U(colStartIdx + indices(i)) += av * values(i)
            i += 1
          }
          j += 1
          prevCol = col
        }
    }
  }
  /**
   * Fills a full square matrix from its upper triangular part.
   */
  private def triuToFull(n: Int, U: Array[Double]): Matrix = {
    val G = new BDM[Double](n, n)
    var row = 0
    var col = 0
    var idx = 0
    var value = 0.0
    while (col < n) {
      row = 0
      while (row < col) {
        value = U(idx)
        G(row, col) = value
        G(col, row) = value
        idx += 1
        row += 1
      }
      G(col, col) = U(idx)
      idx += 1
      col += 1
    }
    Matrices.dense(n, n, G.data)
  }
}

private[marlin] object EigenValueDecomposition {
  /**
   * Compute the leading k eigenvalues and eigenvectors on a symmetric square matrix using ARPACK.
   * The caller needs to ensure that the input matrix is real symmetric. This function requires
   * memory for `n*(4*k+4)` doubles.
   *
   * @param mul a function that multiplies the symmetric matrix with a DenseVector.
   * @param n dimension of the square matrix (maximum Int.MaxValue).
   * @param k number of leading eigenvalues required, 0 < k < n.
   * @param tol tolerance of the eigs computation.
   * @param maxIterations the maximum number of Arnoldi update iterations.
   * @return a dense vector of eigenvalues in descending order and a dense matrix of eigenvectors
   * (columns of the matrix).
   * @note The number of computed eigenvalues might be smaller than k when some Ritz values do not
   * satisfy the convergence criterion specified by tol (see ARPACK Users Guide, Chapter 4.6
   * for more details). The maximum number of Arnoldi update iterations is set to 300 in this
   * function.
   */
  private[marlin] def symmetricEigs(
    mul: BDV[Double] => BDV[Double],
    n: Int,
    k: Int,
    tol: Double,
    maxIterations: Int): (BDV[Double], BDM[Double]) = {
    // TODO: remove this function and use eigs in breeze when switching breeze version
    require(n > k, s"Number of required eigenvalues $k must be smaller than matrix dimension $n")
    val arpack = ARPACK.getInstance()
    // tolerance used in stopping criterion
    val tolW = new doubleW(tol)
    // number of desired eigenvalues, 0 < nev < n
    val nev = new intW(k)
    // nev Lanczos vectors are generated in the first iteration
    // ncv-nev Lanczos vectors are generated in each subsequent iteration
    // ncv must be smaller than n
    val ncv = math.min(2 * k, n)
    // "I" for standard eigenvalue problem, "G" for generalized eigenvalue problem
    val bmat = "I"
    // "LM" : compute the NEV largest (in magnitude) eigenvalues
    val which = "LM"
    var iparam = new Array[Int](11)
    // use exact shift in each iteration
    iparam(0) = 1
    // maximum number of Arnoldi update iterations, or the actual number of iterations on output
    iparam(2) = maxIterations
    // Mode 1: A*x = lambda*x, A symmetric
    iparam(6) = 1
    var ido = new intW(0)
    var info = new intW(0)
    var resid = new Array[Double](n)
    var v = new Array[Double](n * ncv)
    var workd = new Array[Double](n * 3)
    var workl = new Array[Double](ncv * (ncv + 8))
    var ipntr = new Array[Int](11)
    // call ARPACK's reverse communication, first iteration with ido = 0
    arpack.dsaupd(ido, bmat, n, which, nev.`val`, tolW, resid, ncv, v, n, iparam, ipntr, workd,
      workl, workl.length, info)
    val w = BDV(workd)
    // ido = 99 : done flag in reverse communication
    while (ido.`val` != 99) {
      if (ido.`val` != -1 && ido.`val` != 1) {
        throw new IllegalStateException("ARPACK returns ido = " + ido.`val` +
          " This flag is not compatible with Mode 1: A*x = lambda*x, A symmetric.")
      }
      // multiply working vector with the matrix
      val inputOffset = ipntr(0) - 1
      val outputOffset = ipntr(1) - 1
      val x = w.slice(inputOffset, inputOffset + n)
      val y = w.slice(outputOffset, outputOffset + n)
      y := mul(x)
      // call ARPACK's reverse communication
      arpack.dsaupd(ido, bmat, n, which, nev.`val`, tolW, resid, ncv, v, n, iparam, ipntr,
        workd, workl, workl.length, info)
    }
    if (info.`val` != 0) {
      info.`val` match {
        case 1 => throw new IllegalStateException("ARPACK returns non-zero info = " + info.`val` +
          " Maximum number of iterations taken. (Refer ARPACK user guide for details)")
        case 2 => throw new IllegalStateException("ARPACK returns non-zero info = " + info.`val` +
          " No shifts could be applied. Try to increase NCV. " +
          "(Refer ARPACK user guide for details)")
        case _ => throw new IllegalStateException("ARPACK returns non-zero info = " + info.`val` +
          " Please refer ARPACK user guide for error message.")
      }
    }
    val d = new Array[Double](nev.`val`)
    val select = new Array[Boolean](ncv)
    // copy the Ritz vectors
    val z = java.util.Arrays.copyOfRange(v, 0, nev.`val` * n)
    // call ARPACK's post-processing for eigenvectors
    arpack.dseupd(true, "A", select, d, z, n, 0.0, bmat, n, which, nev, tol, resid, ncv, v, n,
      iparam, ipntr, workd, workl, workl.length, info)
    // number of computed eigenvalues, might be smaller than k
    val computed = iparam(4)
    val eigenPairs = java.util.Arrays.copyOfRange(d, 0, computed).zipWithIndex.map { r =>
      (r._1, java.util.Arrays.copyOfRange(z, r._2 * n, r._2 * n + n))
    }
    // sort the eigen-pairs in descending order
    val sortedEigenPairs = eigenPairs.sortBy(-_._1)
    // copy eigenvectors in descending order of eigenvalues
    val sortedU = BDM.zeros[Double](n, computed)
    sortedEigenPairs.zipWithIndex.foreach { r =>
      val b = r._2 * n
      var i = 0
      while (i < n) {
        sortedU.data(b + i) = r._1._2(i)
        i += 1
      }
    }
    (BDV[Double](sortedEigenPairs.map(_._1)), sortedU)
  }
}





