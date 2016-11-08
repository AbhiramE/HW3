package edu.umass.cs.cs646.utils;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

public class DumpLuceneDocLength {
	
	public static void main( String[] args ) {
		try {
			
			String pathIndex = args[0];
			String[] fields = args[1].split( ";" );
			
			File dirIndex = new File( pathIndex );
			
			Directory dir = FSDirectory.open( dirIndex.toPath() );
			IndexReader index = DirectoryReader.open( dir );
			
			for ( String field : fields ) {
				File f = FileDocLengthReader.getDocLengthFile( dirIndex, field );
				try {
					System.out.println( " >> Dumping document length for field " + field );
					DataOutputStream dos = new DataOutputStream( new BufferedOutputStream( new FileOutputStream( f ), 16 * 1024 * 1024 ) );
					for ( int docid = 0; docid < index.numDocs(); docid++ ) {
						if ( docid > 0 && docid % 1000000 == 0 ) {
							System.out.println( "   --> finished " + docid + " documents" );
						}
						Terms terms = index.getTermVector( docid, field );
						TermsEnum iterator = terms.iterator();
						int doclen = 0;
						while ( iterator.next() != null ) {
							doclen += (int) iterator.totalTermFreq();
						}
						dos.writeInt( doclen );
					}
					dos.close();
				} catch ( Exception e ) {
					e.printStackTrace();
				}
			}
			
			index.close();
			dir.close();
			
		} catch ( Exception e ) {
			e.printStackTrace();
		}
	}
	
}
