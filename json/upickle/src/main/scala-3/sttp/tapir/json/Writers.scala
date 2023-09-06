package sttp.tapir.json

import _root_.upickle.AttributeTagged
import _root_.upickle.core.Annotator.Checker
import _root_.upickle.core.{ObjVisitor, Visitor, _}
import _root_.upickle.implicits.{macros => upickleMacros}
import sttp.tapir.SchemaType.SProduct
import sttp.tapir.generic.Configuration
import sttp.tapir.Schema

import scala.reflect.ClassTag

import macros.*
import _root_.upickle.implicits.WritersVersionSpecific

trait Writers extends WritersVersionSpecific with UpickleHelpers {

  inline def macroProductW[T: ClassTag](
      schema: Schema[T],
      childWriters: => List[Any],
      childDefaults: => List[Option[Any]],
      subtypeDiscriminator: SubtypeDiscriminator[T]
  )(using
      Configuration
  ) =
    lazy val writer = new CaseClassWriter[T] {
      def length(v: T) = upickleMacros.writeLength[T](outerThis, v)

      val sProduct = schema.schemaType.asInstanceOf[SProduct[T]]

      override def write0[R](out: Visitor[_, R], v: T): R = {
        if (v == null) out.visitNull(-1)
        else {
          val ctx = out.visitObject(length(v), true, -1)
          macros.writeSnippets[R, T](
            sProduct,
            outerThis,
            this,
            v,
            ctx,
            childWriters,
            childDefaults
          )
          ctx.visitEnd(-1)
        }
      }

      def writeToObject[R](ctx: _root_.upickle.core.ObjVisitor[_, R], v: T): Unit =
        macros.writeSnippets[R, T](
          sProduct,
          outerThis,
          this,
          v,
          ctx,
          childWriters,
          childDefaults
        )
    }

    inline if upickleMacros.isMemberOfSealedHierarchy[T] && !isScalaEnum[T] then
      annotate[T](
        writer,
        upickleMacros.tagName[T],
        Annotator.Checker.Cls(implicitly[ClassTag[T]].runtimeClass)
      ) // tagName is responsible for extracting the @tag annotation meaning the discriminator value
    else if upickleMacros.isSingleton[T]
    then // moved after "if MemberOfSealed" to handle case objects in hierarchy as case classes - with discriminator, for consistency
      // here we handle enums
      annotate[T](SingletonWriter[T](null.asInstanceOf[T]), upickleMacros.tagName[T], Annotator.Checker.Val(upickleMacros.getSingleton[T]))
    else writer

  inline def macroSumW[T: ClassTag](inline schema: Schema[T], childWriters: => List[Any], subtypeDiscriminator: SubtypeDiscriminator[T])(
      using Configuration
  ) =
    implicit val currentlyDeriving: _root_.upickle.core.CurrentlyDeriving[T] = new _root_.upickle.core.CurrentlyDeriving()
    val writers: List[TaggedWriter[_ <: T]] = childWriters
      .asInstanceOf[List[TaggedWriter[_ <: T]]]

    new TaggedWriter.Node[T](writers: _*) {
      override def findWriter(v: Any): (String, ObjectWriter[T]) = {
        subtypeDiscriminator match {
          case discriminator: CustomSubtypeDiscriminator[T] =>
            val (tag, w) = super.findWriter(v)
            val overriddenTag = discriminator.writeUnsafe(v) // here we use our discirminator instead of uPickle's
            (overriddenTag, w)
          case discriminator: EnumValueDiscriminator[T] =>
            val (t, writer) = super.findWriter(v)
            val overriddenTag = discriminator.encode(v.asInstanceOf[T])
            (overriddenTag, writer)

          case _: DefaultSubtypeDiscriminator[T] =>
            val (t, writer) = super.findWriter(v)
            (t, writer)
        }
      }
    }
}
