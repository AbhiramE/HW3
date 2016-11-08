import pylab
import numpy as np


def plotGraph(fileName):
	inFile = open(fileName,'r')
	y1=[]
	y2=[]
	x=[]
	for line in inFile:
		line=line.strip('\n')
		fields=line.split('\t')	
		print fields
		
		if(fields[0] != 'Lamda'):
			x.append(float(fields[0]))
			y1.append(float(fields[1]))
			y2.append(float(fields[2]))
	return y1,y2,x

def producePlot(y1,y2,x,title,figName,param):
	fig, ax1 = pylab.subplots()

	fig=pylab.figure(1);
	ax2 = ax1.twinx()
	pylab.title(title);
	ax1.set_ylabel('Mean P@10',color='g');
	ax2.set_ylabel('Mean AP',color='b')
	ax1.set_xlabel(param)
	ax1.plot(x,y1, '-go',)
	ax2.plot(x,y2, '-bo',)
	pylab.show()
	fig.savefig(figName,bbox_inches='tight')

y1,y2,x=plotGraph('LamdaForRM3')
producePlot(y1,y2,x,'Variation of mean P@10 and AP for RM3 with different Lamda',
	'LamdaVsRM3.png','Lamda')
