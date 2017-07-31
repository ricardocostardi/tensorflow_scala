/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.tensorflow.api.ops

import org.platanios.tensorflow.api.core.Indexer.Implicits._
import org.platanios.tensorflow.api.core.Shape
import org.platanios.tensorflow.api.ops.Gradients.{Registry => GradientsRegistry}
import org.platanios.tensorflow.api.ops.NN._
import org.platanios.tensorflow.api.types._

import scala.language.postfixOps

/** Contains functions for constructing ops related to neural networks.
  *
  * @author Emmanouil Antonios Platanios
  */
trait NN {
  /** Creates an op that adds `bias` to `value`.
    *
    * The op is (mostly) a special case of `tf.add` where `bias` is restricted to be one-dimensional (i.e., has rank 1).
    * Broadcasting is supported and so `value` may have any number of dimensions. Unlike `tf.add`, the type of `bias` is
    * allowed to differ from that of value `value` in the case where both types are quantized.
    *
    * @param  value         Value tensor.
    * @param  bias          Bias tensor that must be one-dimensional (i.e., it must have rank 1).
    * @param  cNNDataFormat Data format of the input and output tensors. With the default format [[NHWCFormat]], the
    *                       `bias` tensor will be added to the last dimension of the `value` tensor. Alternatively, the
    *                       format could be [[NCHWFormat]], and the `bias` tensor would be added to the third-to-last
    *                       dimension.
    * @param  name          Name for the created op.
    * @return Created op output.
    * @throws IllegalArgumentException If the `bias` tensor is not one-dimensional.
    */
  @throws[IllegalArgumentException]
  def addBias(
      value: Output, bias: Output, cNNDataFormat: CNNDataFormat = CNNDataFormat.default,
      name: String = "AddBias"): Output = {
    if (bias.rank != 1)
      throw new IllegalArgumentException(s"'bias' (rank = ${bias.rank}) should have rank 1.")
    Op.Builder(opType = "BiasAdd", name = name)
        .addInput(value)
        .addInput(bias)
        .setAttribute("data_format", cNNDataFormat.toString)
        .build().outputs(0)
  }

  /** Creates an op that computes `x * weights + bias`.
    *
    * @param  x       Input tensor.
    * @param  weights Weights tensor.
    * @param  bias    Bias tensor.
    * @param  name    Name for the created op.
    * @return Created op output.
    */
  def linear(x: Output, weights: Output, bias: Output, name: String = "Linear"): Output = {
    Op.createWithNameScope(name, Set(x.op, weights.op, bias.op)) {
      addBias(Math.matmul(x, weights), bias)
    }
  }

  /** Creates an op that computes the rectified linear activation function.
    *
    * The rectified linear activation function is defined as `relu(x) = max(x, 0)`.
    *
    * @param  input Input tensor.
    * @param  name  Name for the created op.
    * @param  alpha Slope of the negative section, also known as leakage parameter. If other than `0.0f`, the negative
    *               part will be equal to `alpha * x` instead of `0`. Defaults to `0`.
    * @return Created op output.
    */
  def relu(input: Output, alpha: Float = 0.0f, name: String = "ReLU"): Output = {
    def reluOp[T: OutputOps](i: T, n: String): T = {
      implicitly[OutputOps[T]]
          .unaryOp(i, o => Op.Builder(opType = "Relu", name = n)
              .addInput(o)
              .build().outputs(0))
    }
    if (alpha == 0.0) {
      reluOp(input, name)
    } else {
      Op.createWithNameScope(name) {
        val positive = reluOp(input, s"$name/PositivePart")
        val negative = reluOp(-input, s"$name/NegativePart")
        positive - (Basic.constant(alpha, negative.dataType, Shape(1)) * negative)
      }
    }
  }

  /** Creates an op that computes the rectified linear 6 activation function.
    *
    * The rectified linear activation function is defined as `relu6(x) = min(max(x, 0), 6)`.
    *
    * Source: [Convolutional Deep Belief Networks on CIFAR-10. A. Krizhevsky](http://www.cs.utoronto.ca/~kriz/conv-cifar10-aug2010.pdf)
    *
    * @param  input Input tensor.
    * @param  name  Name for the created op.
    * @return Created op output.
    */
  def relu6[T: OutputOps](input: T, name: String = "ReLU6"): T = {
    implicitly[OutputOps[T]]
        .unaryOp(input, o => Op.Builder(opType = "Relu6", name = name)
            .addInput(o)
            .build().outputs(0))
  }

  /** Helper function for [[softmax]] and [[logSoftmax]] that reshapes and transposes the input logits into
    * two-dimensional tensors and then creates the corresponding native op. The output is transposed and reshaped
    * back. */
  private[this] def softmaxHelper(logits: Output, opType: String, axis: Int = -1, name: String = "Softmax"): Output = {
    // We need the original shape of the logits for shape inference.
    val shape = logits.shape
    val isLastAxis = axis == -1 || axis == shape.rank - 1
    if (shape.rank == 2 && isLastAxis) {
      Op.Builder(opType = opType, name = name)
          .addInput(logits)
          .build().outputs(0)
    } else if (isLastAxis) {
      Op.createWithNameScope(name) {
        // If axis is the last axis, we simply reshape the logits to a matrix and apply the internal softmax.
        val inputShape = Basic.shape(logits)
        val flattenedLogits = flattenOuterAxes(logits)
        val output = Op.Builder(opType = opType, name = name)
            .addInput(flattenedLogits)
            .build().outputs(0)
        Basic.reshape(output, inputShape)
      }
    } else {
      Op.createWithNameScope(name) {
        // If axis is not the last dimension, we have to do a reshape and transpose so that we can still perform softmax
        // on its last dimension.
        // We swap the logits' axis of axis and its last axis.
        val inputRank = Basic.rank(logits)
        val swappedLogits = swapAxes(logits, axis, Math.subtract(inputRank, 1))
        val shapeAfterSwap = Basic.shape(swappedLogits)
        // We reshape the logits into a matrix.
        val flattenedLogits = flattenOuterAxes(swappedLogits)
        // We perform the actual softmax on the last axis.
        var output = Op.Builder(opType = opType, name = name)
            .addInput(flattenedLogits)
            .build().outputs(0)
        // We transform back the output tensor.
        output = Basic.reshape(output, shapeAfterSwap)
        output = swapAxes(output, axis, Math.subtract(inputRank, 1))
        // We make shape inference work since the reshape and the transpose may erase the static shape information.
        output.setShape(shape)
        output
      }
    }
  }

  /** Creates an op that computes softmax activations.
    *
    * For each batch `i` and class `j` we have `softmax = exp(logits) / sum(exp(logits), axis)`, where `axis` indicates
    * the axis the softmax should be performed on.
    *
    * @param  logits Tensor containing the logits with data type `HALF`, `FLOAT32`, or `FLOAT64`.
    * @param  axis   Axis along which to perform the softmax. Defaults to `-1` denoting the last axis.
    * @param  name   Name for the created op.
    * @return Created op output.
    */
  def softmax(logits: Output, axis: Int = -1, name: String = "Softmax"): Output = {
    softmaxHelper(logits, "Softmax", axis, name)
  }

  /** Creates an op that computes log-softmax activations.
    *
    * For each batch `i` and class `j` we have `log_softmax = logits - log(sum(exp(logits), axis))`, where `axis`
    * indicates the axis the log-softmax should be performed on.
    *
    * @param  logits Tensor containing the logits with data type `HALF`, `FLOAT32`, or `FLOAT64`.
    * @param  axis   Axis along which to perform the log-softmax. Defaults to `-1` denoting the last axis.
    * @param  name   Name for the created op.
    * @return Created op output.
    */
  def logSoftmax(logits: Output, axis: Int = -1, name: String = "LogSoftmax"): Output = {
    softmaxHelper(logits, "LogSoftmax", axis, name)
  }

  /** Creates an op that computes the softmax cross entropy between `logits` and `labels`.
    *
    * The op measures the probabilistic error in discrete classification tasks in which the classes are mutually
    * exclusive (each entry belongs to exactly one class). For example, each CIFAR-10 image is labeled with one and only
    * one label: an image can be a dog or a truck, but not both.
    *
    * **NOTE:** While the classes are mutually exclusive, their probabilities need not be. All that is required is that
    * each row of `labels` is a valid probability distribution. If they are not, the computation of the gradient will be
    * incorrect. If using exclusive `labels` (wherein one and only one class is true at a time), see
    * [[sparseSoftmaxCrossEntropy]].
    *
    * **WARNING:** The op expects unscaled logits, since it performs a `softmax` on `logits` internally for efficiency.
    * Do not call this op with the output of `softmax`, as it will produce incorrect results.
    *
    * `logits` and `labels` must have the same shape. A common use case if to have `logits` and `labels` of shape
    * `[batchSize, numClasses]`, but higher dimensions are also supported.
    *
    * `logits` and `labels` must have data type `FLOAT16`, `FLOAT32`, or `FLOAT64`.
    *
    * @param  logits Tensor of shape `[D0, D1, ..., Dr-1, numClasses]` and data type `FLOAT16`, `FLOAT32`, or `FLOAT64`,
    *                containing unscaled log probabilities.
    * @param  labels Tensor of shape `[D0, D1, ..., Dr-1, numClasses]` and data type `FLOAT16`, `FLOAT32`, or `FLOAT64`,
    *                where each row must be a valid probability distribution.
    * @param  axis   The class axis, along which the softmax is computed. Defaults to `-1`, which is the last axis.
    * @param  name   Name for the created op.
    * @return Created op output, with rank one less than that of `logits` and the same data type as `logits`, containing
    *         the softmax cross entropy loss.
    * @throws IllegalArgumentException If `logits` or `labels` have an unsupported data type or incompatible shapes.
    */
  @throws[IllegalArgumentException]
  def softmaxCrossEntropy(
      logits: Output, labels: Output, axis: Int = -1, name: String = "SoftmaxCrossEntropy"): Output = {
    if (logits.dataType != FLOAT16 && logits.dataType != FLOAT32 && logits.dataType != FLOAT64)
      throw new IllegalArgumentException(
        s"Encountered unsupported data type: ${logits.dataType}. " +
            "'logits' must have data type of FLOAT16, FLOAT32, or FLOAT64.")
    if (!logits.shape.isCompatibleWith(labels.shape))
      throw new IllegalArgumentException(
        s"The 'logits' shape (${logits.shape}) and the 'labels' shape (${labels.shape}) must be compatible.")
    Op.createWithNameScope(name, Set(logits.op, labels.op)) {
      // Labels and logits must be of the same data type.
      val preciseLogits = if (logits.dataType == FLOAT16) Math.cast(logits, FLOAT32) else logits
      val preciseLabels = Math.cast(labels, preciseLogits.dataType)
      val inputRank = Basic.rank(preciseLogits)
      // We need the original shape of the logits for shape inference.
      val shape = logits.shape
      // We move axis to the end, if it's not already the last axis.
      val transposedLogits = moveAxisToEnd(preciseLogits, axis, inputRank)
      val transposedLabels = moveAxisToEnd(preciseLabels, axis, inputRank)
      val inputShape = Basic.shape(preciseLogits)
      // Flatten transposedLogits and transposedLabels into matrices.
      val flattenedLogits = flattenOuterAxes(transposedLogits)
      val flattenedLabels = flattenOuterAxes(transposedLabels)
      // Create the native op.
      // The second output tensor contains the gradients, which is used for the gradient computation.
      val output = Op.Builder(opType = "SoftmaxCrossEntropyWithLogits", name = name)
          .addInput(flattenedLogits)
          .addInput(flattenedLabels)
          .build().outputs(0)
      // The output shape should be the input shape without the axis over which the cross entropy was computed.
      val outputShape = Basic.slice(
        inputShape,
        Basic.constant(0, inputShape.dataType, Shape(1)),
        Basic.expandDims(Math.subtract(inputRank, 1), -1))
      val reshapedOutput = Basic.reshape(output, outputShape)
      // We make shape inference work since the reshape and the transpose may erase the static shape information.
      if (shape.rank > -1) {
        def removeAt(array: Array[Int], axis: Int): Array[Int] = axis match {
          case a if a < 0 => removeAt(array, axis + array.length)
          case a if a == 0 => array.drop(1)
          case a if a == array.length => array.dropRight(1)
          case _ => array.take(axis) ++ array.drop(axis + 1)
        }
        reshapedOutput.setShape(Shape(removeAt(shape.asArray, axis): _*))
      }
      // We cast back to the original logits data type, if necessary.
      if (logits.dataType == FLOAT16)
        Math.cast(reshapedOutput, FLOAT16)
      else
        reshapedOutput
    }
  }

  /** Creates an op that computes the sparse softmax cross entropy between `logits` and `labels`.
    *
    * The op measures the probabilistic error in discrete classification tasks in which the classes are mutually
    * exclusive (each entry belongs to exactly one class). For example, each CIFAR-10 image is labeled with one and only
    * one label: an image can be a dog or a truck, but not both.
    *
    * **NOTE:** For the op, the probability of a given label is considered exclusive. That is, soft classes are not
    * allowed, and the `labels` vector must provide a single specific index for the true class for each row of `logits`
    * (i.e., each batch instance). For soft softmax classification with a probability distribution for each entry, see
    * [[softmaxCrossEntropy]].
    *
    * **WARNING:** The op expects unscaled logits, since it performs a `softmax` on `logits` internally for efficiency.
    * Do not call this op with the output of `softmax`, as it will produce incorrect results.
    *
    * A common use case if to have `logits` of shape `[batchSize, numClasses]` and `labels` of shape `[batchSize]`, but
    * higher dimensions are also supported.
    *
    * `logits` must have data type `FLOAT16`, `FLOAT32`, or `FLOAT64`, and `labels` must have data type `INT32` or
    * `INT64`.
    *
    * @param  logits Tensor of shape `[D0, D1, ..., Dr-1, numClasses]` (where `r` is the rank of `labels` and of the
    *                result) and data type `FLOAT16`, `FLOAT32`, or `FLOAT64`, containing unscaled log probabilities.
    * @param  labels Tensor of shape `[D0, D1, ..., Dr-1]` (where `r` is the rank of `labels` and of the result) and
    *                data type `INT32` or `INT64`. Each entry in `labels` must be an index in `[0, numClasses)`. Other
    *                values will raise an exception when this op is run on a CPU, and return `NaN` values for the
    *                corresponding loss and gradient rows when this op is run on a GPU.
    * @param  axis   The class axis, along which the softmax is computed. Defaults to `-1`, which is the last axis.
    * @param  name   Name for the created op.
    * @return Created op output, with the same shape as `labels` and the same data type as `logits`, containing the
    *         softmax cross entropy loss.
    * @throws IllegalArgumentException If `logits` or `labels` have an unsupported data type or incompatible shapes.
    */
  @throws[IllegalArgumentException]
  def sparseSoftmaxCrossEntropy(
      logits: Output, labels: Output, axis: Int = -1, name: String = "SparseSoftmaxCrossEntropy"): Output = {
    if (logits.dataType != FLOAT16 && logits.dataType != FLOAT32 && logits.dataType != FLOAT64)
      throw new IllegalArgumentException(
        s"Encountered unsupported data type: ${logits.dataType}. " +
            "'logits' must have data type of FLOAT16, FLOAT32, or FLOAT64.")
    if (labels.dataType != INT32 && labels.dataType != INT64)
      throw new IllegalArgumentException(
        s"Encountered unsupported data type: ${labels.dataType}. 'labels' must have data type of INT32 or INT64.")
    Op.createWithNameScope(name, Set(logits.op, labels.op)) {
      val preciseLogits = if (logits.dataType == FLOAT16) Math.cast(logits, FLOAT32) else logits
      if (logits.rank == 0)
        throw new IllegalArgumentException(s"The 'logits' (shape = ${logits.shape}) cannot be scalars.")
      if (labels.rank != logits.rank - 1)
        throw new IllegalArgumentException(
          s"The rank of the 'labels' (rank = ${labels.rank}) must be equal to " +
              s"the rank of 'logits' (rank = ${logits.rank}) minus 1.")
      // Check if no reshapes are required.
      val output = {
        if (logits.rank == 2) {
          // Create the native op.
          // The second output tensor contains the gradients, which is used for the gradient computation.
          Op.Builder(opType = "SparseSoftmaxCrossEntropyWithLogits", name = name)
              .addInput(preciseLogits)
              .addInput(labels)
              .build().outputs(0)
        } else {
          // Reshape logits to rank 2 and labels to rank 1.
          val flattenedLogits = flattenOuterAxes(preciseLogits)
          val flattenedLabels = Basic.reshape(labels, -1)
          // Create the native op.
          // The second output tensor contains the gradients, which is used for the gradient computation.
          val output = Op.Builder(opType = "SparseSoftmaxCrossEntropyWithLogits", name = name)
              .addInput(flattenedLogits)
              .addInput(flattenedLabels)
              .build().outputs(0)
          val reshapedOutput = Basic.reshape(output, Basic.shape(labels))
          reshapedOutput.setShape(labels.shape)
          reshapedOutput
        }
      }
      // We cast back to the original logits data type, if necessary.
      if (logits.dataType == FLOAT16)
        Math.cast(output, FLOAT16)
      else
        output
    }
  }
}

object NN extends NN {
  /** Creates an op that flattens the outer axes of `input` and keeps its last axis. */
  private[ops] def flattenOuterAxes(input: Output): Output = {
    val rank = Basic.rank(input)
    val lastAxisSize = Basic.slice(
      Basic.shape(input),
      Basic.expandDims(Math.subtract(rank, 1), -1),
      Basic.constant(1, rank.dataType, Shape(1)))
    val output = Basic.reshape(input, Basic.concatenate(
      Seq(Basic.constant(-1, lastAxisSize.dataType, Shape(1)), lastAxisSize), 0))
    // Set the output shape, if known.
    val shape = input.shape
    if (shape.rank != -1) {
      val product = shape(0 :: -1).asArray.product
      if (product > -1)
        output.setShape(Shape(product, shape(-1)))
    }
    output
  }

  /** Creates an op that swaps the axes `axis1` and `axis2` in `input` and ignores all axes after `axis2`. */
  private[ops] def swapAxes(input: Output, axis1: Output, axis2: Output, name: String = "SwapAxes"): Output = {
    Basic.transpose(
      input,
      Basic.concatenate(Seq(
        Math.range(0, axis1),
        axis2,
        Math.range(axis1 + 1, axis2),
        axis1), 0),
      name)
  }

  /** Creates an op that moves `axis` to the end. */
  private[ops] def moveAxisToEnd(input: Output, axis: Int, rank: Output, name: String = "SwapAxes"): Output = {
    if (axis == -1) {
      input
    } else {
      val axisOutput = Basic.constant(axis, rank.dataType, Shape())
      Basic.transpose(
        input,
        Basic.concatenate(Seq(
          Math.range(0, axisOutput),
          Math.range(axisOutput + 1, rank),
          axisOutput), 0),
        name)
    }
  }

  sealed trait CNNDataFormat {
    val name: String
    override def toString: String = name
  }
  case object NHWCFormat extends CNNDataFormat {override val name: String = "NHWC"}
  case object NCHWFormat extends CNNDataFormat {override val name: String = "NCHW"}

  object CNNDataFormat {
    val default = NHWCFormat

    def fromName(name: String): CNNDataFormat = fromString(name)

    @throws[IllegalArgumentException]
    def fromString(name: String): CNNDataFormat = name match {
      case "NHWC" => NHWCFormat
      case "NCHW" => NCHWFormat
      case _ => throw new IllegalArgumentException(s"Unsupported CNN data format string: '$name'.")
    }
  }

  private[api] object Gradients {
    GradientsRegistry.register("BiasAdd", biasAddGradient)
    GradientsRegistry.register("BiasAddGrad", biasAddHessian)
    GradientsRegistry.register("Relu", reluGradient)
    GradientsRegistry.register("ReluGrad", reluHessian)
    GradientsRegistry.register("Relu6", relu6Gradient)
    GradientsRegistry.register("Relu6Grad", relu6Hessian)
    GradientsRegistry.register("Softmax", softmaxGradient)
    GradientsRegistry.register("LogSoftmax", logSoftmaxGradient)
    GradientsRegistry.register("SoftmaxCrossEntropyWithLogits", softmaxCrossEntropyGradient)
    GradientsRegistry.register("SparseSoftmaxCrossEntropyWithLogits", sparseSoftmaxCrossEntropyGradient)

    private[this] def biasAddGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      val outputGradient = outputGradients.head.toOutput
      val cNNDataFormatName = {
        try {
          op.stringAttribute("data_format")
        } catch {
          case _: Throwable => CNNDataFormat.default.toString
        }
      }
      val gradient = Op.Builder(opType = "BiasAddGrad", name = "BiasAddGradient")
          .addInput(outputGradient)
          .setAttribute("data_format", cNNDataFormatName)
          .build().outputs(0)
      outputGradients :+ gradient
    }

    private[this] def biasAddHessian(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      val outputGradient = outputGradients.head.toOutput
      val cNNDataFormatName = {
        try {
          op.stringAttribute("data_format")
        } catch {
          case _: Throwable => CNNDataFormat.default.toString
        }
      }
      val valueShape = Basic.shape(op.inputs(0))
      val biasShape = Basic.shape(outputGradient)
      val (expandedShape, tileMultiples) = cNNDataFormatName match {
        case "NHWC" =>
          val valuesLeft = valueShape(0 :: -1)
          val expandedShape = Basic.concatenate(Seq(Basic.onesLike(valuesLeft), biasShape), axis = 0)
          val tileMultiples = Basic.concatenate(Seq(valuesLeft, 1), 0)
          (expandedShape, tileMultiples)
        case "NCHW" =>
          val valuesLeft = valueShape(0 :: -3)
          val valuesRight = valueShape(-2 ::)
          val expandedShape = Basic.concatenate(
            Seq(Basic.onesLike(valuesLeft), biasShape, Basic.onesLike(valuesRight)), axis = 0)
          val tileMultiples = Basic.concatenate(Seq(valuesLeft, 1, valuesRight), 0)
          (expandedShape, tileMultiples)
      }
      Seq(Basic.tile(Basic.reshape(outputGradient, expandedShape), tileMultiples))
    }

    private[this] def reluGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      val outputGradient = outputGradients.head.toOutput
      Seq(Op.Builder(opType = "ReluGrad", name = "ReLUGradient")
              .addInput(outputGradient)
              .addInput(op.outputs(0))
              .build().outputs(0))
    }

    private[this] def reluHessian(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      val outputGradient = outputGradients.head.toOutput
      val x = op.inputs(1)
      Seq(Op.Builder(opType = "ReluGrad", name = "ReLUHessian")
              .addInput(outputGradient)
              .addInput(x)
              .build().outputs(0), Basic.zerosLike(x))
    }

    private[this] def relu6Gradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      val outputGradient = outputGradients.head.toOutput
      Seq(Op.Builder(opType = "Relu6Grad", name = "ReLU6Gradient")
              .addInput(outputGradient)
              .addInput(op.outputs(0))
              .build().outputs(0))
    }

    private[this] def relu6Hessian(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      val outputGradient = outputGradients.head.toOutput
      val x = op.inputs(1)
      Seq(Op.Builder(opType = "Relu6Grad", name = "ReLU6Hessian")
              .addInput(outputGradient)
              .addInput(x)
              .build().outputs(0), Basic.zerosLike(x))
    }

    private[this] def softmaxGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      val outputGradient = outputGradients.head.toOutput
      val softmax = op.outputs(0)
      Seq((outputGradient - Math.sum(outputGradient * softmax, 1, keepDims = true)) * softmax)
    }

    private[this] def logSoftmaxGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      val outputGradient = outputGradients.head.toOutput
      val softmax = Math.exp(op.outputs(0))
      Seq((outputGradient - Math.sum(outputGradient * softmax, 1, keepDims = true)) * softmax)
    }

    private[this] def broadcastMultiply(vector: Output, matrix: Output): Output = {
      Basic.expandDims(vector, -1) * matrix
    }

    private[this] def softmaxCrossEntropyGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      // outputGradients(0) is the back-propagated gradient for the cross entropy, and we multiply it with the gradients
      // (which is op.outputs(1)). outputGradients(1) is the back-propagated gradient for the softmax gradient. There is
      // no gradient for the labels.
      val lossGradient = outputGradients(0).toOutput
      val gradGradient = outputGradients(1).toOutput
      val softmaxGradient = op.outputs(1)
      val outputGradient = broadcastMultiply(lossGradient, softmaxGradient)

      // Some introspection to check if the gradient is feeding zeros.
      val isGradGradientZero = {
        if (gradGradient.op.opType == "Zeros" || gradGradient.op.opType == "ZerosLike") {
          true
        } else {
          val constantFillValue = Output.constantValue(gradGradient)
          constantFillValue.isDefined && constantFillValue.get.entriesIterator.forall(_ == 0)
        }
      }

      if (!isGradGradientZero) {
        val logits = op.inputs(0)
        val logitsSoftmax = NN.softmax(logits)
        val gradient = outputGradient + (
            (gradGradient - Basic.squeeze(
              Math.matmul(
                Basic.expandDims(gradGradient, 1),
                Basic.expandDims(logitsSoftmax, 2)),
              Array(1))) * logitsSoftmax)
        Seq(gradient, null)
      } else {
        Seq(outputGradient, null)
      }
    }

    private[this] def sparseSoftmaxCrossEntropyGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      // outputGradients(0) is the back-propagated gradient for the cross entropy, and we multiply it with the gradients
      // (which is op.outputs(1)). There is no gradient for the labels.
      val lossGradient = outputGradients(0).toOutput
      // Currently there is no way to take the second derivative of this op due to the fused implementation's
      // interaction with tf.gradients(). Therefore, we make sure we silently prevent incorrect results by raising an
      // error if the second derivative is requested via Basic.preventGradient().
      val softmaxGradient = Basic.preventGradient(
        op.outputs(1), message = "Currently there is no way to take the second derivative of " +
            "SparseSoftmaxCrossEntropyWithLogits due to the fused implementation's interaction with tf.gradients().")
      Seq(broadcastMultiply(lossGradient, softmaxGradient), null)
    }
  }
}
