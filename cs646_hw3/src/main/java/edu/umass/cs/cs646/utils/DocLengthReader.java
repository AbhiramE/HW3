package edu.umass.cs.cs646.utils;

import java.io.IOException;

public interface DocLengthReader {
	
	int getLength( int docid ) throws IOException;
	
	void close() throws IOException;
	
}
