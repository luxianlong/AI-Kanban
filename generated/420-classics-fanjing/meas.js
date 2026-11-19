const sharp=require('sharp');
(async()=>{
 const f=process.argv[2];
 const {data,info}=await sharp(f).greyscale().raw().toBuffer({resolveWithObject:true});
 const W=info.width,H=info.height;
 const col=new Float64Array(W), row=new Float64Array(H);
 for(let y=0;y<H;y++){let s=0;for(let x=0;x<W;x++){const v=data[y*W+x];col[x]+=v;s+=v;}row[y]=s/W;}
 for(let x=0;x<W;x++)col[x]/=H;
 const dump=(a,n,lbl)=>{let out=[];for(let i=0;i<a.length;i+=n)out.push(i+':'+a[i].toFixed(0));console.log(lbl,out.join(' '));};
 dump(col,50,'COL');dump(row,50,'ROW');
 console.log('W,H',W,H);
})();
