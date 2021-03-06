package spinoco.fs2.cassandra

import java.nio.ByteBuffer

import com.datastax.driver.core._
import shapeless.ops.hlist.Tupler
import shapeless.ops.product.ToHList
import shapeless.ops.record.Values
import shapeless.{::, HList, HNil, LabelledGeneric}


/** type safe statement against cassandra **/
sealed trait CStatement[I] {
  /** raw cql statement used for preparing the statement **/
  def cqlStatement:String
  /** fills prepared statement with any `I` to form bound statement **/
  def fill(i:I, s:PreparedStatement, protocolVersion: ProtocolVersion):BoundStatement


}

/** DML Statement (INSERT, UPDATE, DELETE) **/
sealed trait DMLStatement[I,O] extends CStatement[I] {

  /** reads the result received from executing the statement **/
  def read(r:ResultSet, protocolVersion: ProtocolVersion):Either[Throwable,O]

  /** for batch results, this allows to read result realted to this batched statement **/
  def readBatchResult(i:I):BatchResultReader[O]
}


/**
  * Defines CQL Query that can be run against the C* table.
  */
trait Query[Q,R] extends CStatement[Q] { self =>

  /** raw cql statement **/
  def cqlStatement:String
  /** function that read values from the row with converting them to `R` or presenting read failure **/
  def read(r:Row, protocolVersion: ProtocolVersion):Either[Throwable,R]
  /** function that writes query to their CQL representations **/
  def cqlFor(q:Q):String
  /** function that writes query to their raw values **/
  def writeRaw(q:Q, protocolVersion: ProtocolVersion):Map[String,ByteBuffer]

  /** allows to modify input Query by `f` **/
  def mapIn[I](f: I => Q):Query[I,R] = {
    new Query[I,R] {
      def cqlStatement: String = self.cqlStatement
      def cqlFor(q: I): String = self.cqlFor(f(q))
      def writeRaw(q: I, protocolVersion: ProtocolVersion): Map[String, ByteBuffer] = self.writeRaw(f(q), protocolVersion)
      def read(r: Row, protocolVersion: ProtocolVersion): Either[Throwable, R] = self.read(r,protocolVersion)
      def fill(i: I, s: PreparedStatement, protocolVersion: ProtocolVersion): BoundStatement = self.fill(f(i),s,protocolVersion)
      override def toString: String = self.toString
    }
  }

  /** allows to modify output result by `f` **/
  def map[O](f: R => O):Query[Q,O] = {
    new Query[Q,O] {
      def cqlStatement: String = self.cqlStatement
      def cqlFor(q: Q): String = self.cqlFor(q)
      def writeRaw(q: Q, protocolVersion: ProtocolVersion): Map[String, ByteBuffer] = self.writeRaw(q, protocolVersion)
      def read(r: Row, protocolVersion: ProtocolVersion): Either[Throwable, O] = self.read(r,protocolVersion).right.map(f)
      def fill(i: Q, s: PreparedStatement, protocolVersion: ProtocolVersion): BoundStatement = self.fill(i,s,protocolVersion)
      override def toString: String = self.toString
    }
  }

}


object Query {

  implicit class QuerySyntaxQH[Q <: HList, R](val self: Query[Q,R]) extends AnyVal {

    /** converts query to read queried parameters from `A` instead of `Q` **/
    def from[A](implicit G:LabelledGeneric.Aux[A,Q]):Query[A,R] = self.mapIn(G.to)

    /** converts query to read from single `A` if input requires only single parameter **/
    def fromA[A](implicit V: Values.Aux[Q, A :: HNil]):Query[A,R] = self.mapIn(a => (a :: HNil).asInstanceOf[Q])

    /** reads from given supplied HList. value types must be in same order as `Q` record **/
    def fromHList[L <: HList](implicit V: Values.Aux[Q,L]):Query[L,R] = self.mapIn(_.asInstanceOf[Q])


    /** reads delete from supplied tuple. Explicit tuple type must be provided **/
    def fromTuple[T](implicit T:ToHList.Aux[T,Q]):Query[T,R] = self.mapIn[T](T(_))
  }


  implicit class QuerySyntaxRH[Q, R <: HList](val self: Query[Q,R]) extends AnyVal {

    /** converts result of query to to `A` **/
    def as[A](implicit G:LabelledGeneric.Aux[A,R]):Query[Q,A] = self.map(G.from)

    /** returns result as Hlist, stripping any key types **/
    def asHlist(implicit V: Values[R]):Query[Q,V.Out] = self.map(V(_))

    /** returns result as tuple **/
    def asTuple(implicit T: Tupler[R]):Query[Q,T.Out] = self.map(T(_))

    /** returns the result as single `A` if the hlist if of single element **/
    def asA[A](implicit V:Values.Aux[R, A :: HNil]):Query[Q,A] = self.map(V(_).head)

  }


}













/**
  * Update statement that will persists `S` in table.
  * `S` is guaranteed to have Primary Key reference.
  */
trait Update[Q,R] extends DMLStatement[Q,R] { self =>
  /** raw cql statement **/
  def cqlStatement:String
  /** function that read values from the row with converting them to `R` or presenting read failure **/
  def read(r:Row, protocolVersion: ProtocolVersion):Either[Throwable,R]
  /** function that writes query `Q` to their CQL representations **/
  def cqlFor(q:Q):String
  /** function that writes query `Q` to their raw values **/
  def writeRaw(q:Q, protocolVersion: ProtocolVersion):Map[String,ByteBuffer]

  /** allows to modify input Q by applying  `f` **/
  def mapIn[B](f: B => Q):Update[B,R] = new Update[B,R]{
    def cqlStatement: String = self.cqlStatement
    def cqlFor(q: B): String = self.cqlFor(f(q))
    def writeRaw(q: B, protocolVersion: ProtocolVersion): Map[String, ByteBuffer] = self.writeRaw(f(q), protocolVersion)
    def read(r: Row, protocolVersion: ProtocolVersion): Either[Throwable, R] = self.read(r,protocolVersion)
    def read(r: ResultSet, protocolVersion: ProtocolVersion): Either[Throwable, R] = self.read(r,protocolVersion)
    def fill(i: B, s: PreparedStatement, protocolVersion: ProtocolVersion): BoundStatement = self.fill(f(i),s,protocolVersion)
    def readBatchResult(i: B): BatchResultReader[R] = self.readBatchResult(f(i))
    override def toString: String = self.toString
  }


  def map[B](f: R => B):Update[Q,B] = new Update[Q,B] {
    def cqlStatement: String = self.cqlStatement
    def cqlFor(q: Q): String = self.cqlFor(q)
    def writeRaw(q: Q, protocolVersion: ProtocolVersion): Map[String, ByteBuffer] = self.writeRaw(q,protocolVersion)
    def read(r: Row, protocolVersion: ProtocolVersion): Either[Throwable, B] = self.read(r,protocolVersion).right.map(f)
    def read(r: ResultSet, protocolVersion: ProtocolVersion): Either[Throwable, B] = self.read(r,protocolVersion).right.map(f)
    def fill(i: Q, s: PreparedStatement, protocolVersion: ProtocolVersion): BoundStatement = self.fill(i,s,protocolVersion)
    def readBatchResult(i: Q): BatchResultReader[B] = self.readBatchResult(i).map(f)
    override def toString: String = self.toString
  }

}


object Update {

  implicit class UpdateQSyntax[Q <: HList, R](val self:Update[Q,R]) extends AnyVal {
    /** transforms update to read from given class `A` **/
    def from[A](implicit G:LabelledGeneric.Aux[A,Q]):Update[A,R] = self.mapIn(G.to)

    /** reasd from givne supplied HList. value types must be in same order as `Q` record **/
    def fromHList[L <: HList](implicit V: Values.Aux[Q,L]):Update[L,R] = self.mapIn(_.asInstanceOf[Q])

    /** reads delete from supplied tuple. Explicit tuple type must be provided **/
    def fromTuple[T](implicit T:ToHList.Aux[T,Q]):Update[T,R] = self.mapIn(T(_))

  }


  implicit class UpdateRSyntax[Q, R <: HList](val self:Update[Q,R]) extends AnyVal {
    /** converts result of update to to `A` **/
    def as[A](implicit G:LabelledGeneric.Aux[A,R]):Update[Q,A] = self.map(G.from)

    def asA[A](implicit V: Values.Aux[R, A :: HNil]):Update[Q,A] = self.map(V(_).head)

    /** returns result as Hlist, stripping any key types **/
    def asHlist(implicit V: Values[R]):Update[Q,V.Out] = self.map(V(_))

    /** returns result as tuple **/
    def asTuple(implicit T: Tupler[R]):Update[Q,T.Out] = self.map(T(_))
  }


}










trait Delete[Q,R] extends DMLStatement[Q,R] { self =>
  /** raw cql statement **/
  def cqlStatement:String
  /** function that read values from the row with converting them to `R` or presenting read failure **/
  def read(r:Row, protocolVersion: ProtocolVersion):Either[Throwable,R]
  /** function that writes query `Q` to their CQL representations **/
  def cqlFor(q:Q):String
  /** function that writes query `Q` to their raw values **/
  def writeRaw(q:Q, protocolVersion: ProtocolVersion):Map[String,ByteBuffer]

  def mapIn[Q2](f: Q2 => Q):Delete[Q2,R] = new Delete[Q2,R] {
    def cqlStatement: String = self.cqlStatement
    def cqlFor(q: Q2): String = self.cqlFor(f(q))
    def writeRaw(q: Q2, protocolVersion: ProtocolVersion): Map[String, ByteBuffer] = self.writeRaw(f(q),protocolVersion)
    def read(r: Row, protocolVersion: ProtocolVersion): Either[Throwable, R] = self.read(r,protocolVersion)
    def read(r: ResultSet, protocolVersion: ProtocolVersion): Either[Throwable, R] = self.read(r,protocolVersion)
    def fill(q: Q2, s: PreparedStatement, protocolVersion: ProtocolVersion): BoundStatement = self.fill(f(q),s,protocolVersion)
    def readBatchResult(i: Q2): BatchResultReader[R] = self.readBatchResult(f(i))
    override def toString: String = self.toString
  }

  def map[R2](f: R => R2):Delete[Q,R2] = new Delete[Q,R2] {
    def cqlStatement: String = self.cqlStatement
    def cqlFor(q: Q): String = self.cqlFor(q)
    def writeRaw(q: Q, protocolVersion: ProtocolVersion): Map[String, ByteBuffer] = self.writeRaw(q,protocolVersion)
    def read(r: Row, protocolVersion: ProtocolVersion): Either[Throwable, R2] = self.read(r,protocolVersion).right.map(f)
    def read(r: ResultSet, protocolVersion: ProtocolVersion): Either[Throwable, R2] = self.read(r,protocolVersion).right.map(f)
    def fill(i: Q, s: PreparedStatement, protocolVersion: ProtocolVersion): BoundStatement = self.fill(i,s,protocolVersion)
    def readBatchResult(i: Q): BatchResultReader[R2] = self.readBatchResult(i).map(f)
    override def toString: String = self.toString
  }

}


object Delete {

  implicit class DeleteQSyntax[Q <: HList, R](val self:Delete[Q,R]) extends AnyVal {
    /** transforms update to read from given class `A` **/
    def from[A](implicit G:LabelledGeneric.Aux[A,Q]):Delete[A,R] = self.mapIn(G.to)

    def fromA[A](implicit V: Values.Aux[Q, A :: HNil]):Delete[A,R] = self.mapIn { a => (a :: HNil).asInstanceOf[Q]}

    /** reads from given supplied HList. value types must be in same order as `Q` record **/
    def fromHList[L <: HList](implicit V: Values.Aux[Q,L]):Delete[L,R] = self.mapIn(_.asInstanceOf[Q])

    /** reads delete from supplied tuple. Explicit tuple type must be provided **/
    def fromTuple[T](implicit T:ToHList.Aux[T,Q]):Delete[T,R] = self.mapIn(T(_))


  }


  implicit class DeleteRSyntax[Q, R <: HList](val self:Delete[Q,R]) extends AnyVal {
    /** retruns result as class `A` given it can be constructed from `R` **/
    def as[A](implicit G:LabelledGeneric.Aux[A,R]):Delete[Q,A] = self.map(G.from)

    /** retruns result as `A` given there is only single result **/
    def asA[A](implicit V: Values.Aux[R, A :: HNil]):Delete[Q,A] = self.map(V(_).head)

    /** returns result as Hlist, stripping any key types **/
    def asHlist(implicit V: Values[R]):Delete[Q,V.Out] = self.map(V(_))

    /** returns result as tuple **/
    def asTuple(implicit T: Tupler[R]):Delete[Q,T.Out] = self.map(T(_))
  }

}







trait Insert[I,O] extends DMLStatement[I,O] { self =>

  /** raw cql statement **/
  def cqlStatement:String
  /** function that read values from the row with converting them to `O` or presenting read failure **/
  def read(r:Row, protocolVersion: ProtocolVersion):Either[Throwable,O]
  /** function that writes query to their CQL representations **/
  def cqlFor(i:I):String
  /** function that writes query to their raw values **/
  def writeRaw(i:I, protocolVersion: ProtocolVersion):Map[String,ByteBuffer]

  /** changes input `I` by supplied function `f` **/
  def mapIn[I2](f: I2 => I):Insert[I2,O] = {
    new Insert[I2,O] {
      def cqlStatement: String = self.cqlStatement
      def cqlFor(i: I2): String = self.cqlFor(f(i))
      def writeRaw(i: I2, protocolVersion: ProtocolVersion): Map[String, ByteBuffer] = self.writeRaw(f(i), protocolVersion)
      def read(r: Row, protocolVersion: ProtocolVersion): Either[Throwable, O] = self.read(r,protocolVersion)
      def fill(i: I2, s: PreparedStatement, protocolVersion: ProtocolVersion): BoundStatement = self.fill(f(i),s,protocolVersion)
      def read(r: ResultSet, protocolVersion: ProtocolVersion): Either[Throwable, O] = self.read(r,protocolVersion)
      def readBatchResult(i: I2): BatchResultReader[O] = self.readBatchResult(f(i))
      override def toString: String = self.toString
    }
  }

  /** changes output `O` by supplied function **/
  def map[O2](f: O => O2):Insert[I,O2] = {
    new Insert[I,O2] {
      def cqlStatement: String = self.cqlStatement
      def cqlFor(i: I): String = self.cqlFor(i)
      def writeRaw(i: I, protocolVersion: ProtocolVersion): Map[String, ByteBuffer] = self.writeRaw(i, protocolVersion)
      def read(r: Row, protocolVersion: ProtocolVersion): Either[Throwable, O2] = self.read(r,protocolVersion).right.map(f)
      def fill(i: I, s: PreparedStatement, protocolVersion: ProtocolVersion): BoundStatement = self.fill(i,s,protocolVersion)
      def read(r: ResultSet, protocolVersion: ProtocolVersion): Either[Throwable, O2] = self.read(r,protocolVersion).right.map(f)
      def readBatchResult(i: I): BatchResultReader[O2] = self.readBatchResult(i).map(f)
      override def toString: String = self.toString
    }
  }

}


object Insert {

  implicit class InsertHInputSyntax[I <: HList,O](val self: Insert[I,O]) extends AnyVal {
    def from[A](implicit G:LabelledGeneric.Aux[A,I]):Insert[A,O] = self.mapIn(G.to)
    /** reads from given supplied HList. value types must be in same order as `I` record **/
    def fromHList[L <: HList](implicit V: Values.Aux[I,L]):Insert[L,O] = self.mapIn(_.asInstanceOf[I])
    def fromTuple[A](implicit T:ToHList.Aux[A,I]):Insert[A,O] = self.mapIn(T(_))
  }

  implicit class InsertHOutputSyntax[I,O <: HList](val self: Insert[I,Option[O]]) extends AnyVal {
    def as[A](implicit G:LabelledGeneric.Aux[A,O]):Insert[I,Option[A]] = self.map(_.map(G.from))
  }

}





